package com.tokenizedtradingplatform.demo.application.service;

import com.tokenizedtradingplatform.demo.application.event.WalletBalanceChangedEvent;
import com.tokenizedtradingplatform.demo.domain.enums.TransactionType;
import com.tokenizedtradingplatform.demo.domain.model.*;
import com.tokenizedtradingplatform.demo.domain.repository.AssetRepository;
import com.tokenizedtradingplatform.demo.domain.repository.OrderRepository;
import com.tokenizedtradingplatform.demo.domain.repository.TradeRepository;
import com.tokenizedtradingplatform.demo.domain.repository.TransactionRepository;
import com.tokenizedtradingplatform.demo.domain.repository.WalletRepository;
import com.tokenizedtradingplatform.demo.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Atomic 4-wallet settlement. Joins the caller's transaction (REQUIRED).
 *
 * For one match (buyOrder, sellOrder, tradePrice, qty):
 *   1. Lock all 4 wallets in UUID-sorted order (deadlock-free).
 *   2. buyer.cash.settleDebit(qty * tradePrice)             — from locked → gone to seller
 *      buyer.cash.releaseReservation(refund)                — extra reserved at higher limit
 *      seller.asset.settleDebit(qty)                        — from locked → gone to buyer
 *      buyer.asset.credit(qty)
 *      seller.cash.credit(qty * tradePrice)
 *   3. Save Trade + 4 Transaction ledger rows.
 *   4. Update filled_quantity / status on both orders.
 */
@Service
@RequiredArgsConstructor
public class TradeSettlementService {

    private static final int CASH_ASSET_ID = 1;

    private final WalletRepository walletRepository;
    private final TradeRepository tradeRepository;
    private final TransactionRepository transactionRepository;
    private final OrderRepository orderRepository;
    private final AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRED)
    public Trade settle(Order buyOrder, Order sellOrder, BigDecimal tradePrice, BigDecimal qty) {
        User buyer = buyOrder.getUser();
        User seller = sellOrder.getUser();
        Asset asset = buyOrder.getAsset();
        Asset cash = assetRepository.findById(CASH_ASSET_ID)
                .orElseThrow(() -> new ResourceNotFoundException("CASH asset missing — V2 seed not applied"));

        BigDecimal totalCost = tradePrice.multiply(qty);
        BigDecimal buyerReservedForThisFill = buyOrder.getPrice().multiply(qty);
        BigDecimal refund = buyerReservedForThisFill.subtract(totalCost);

        // Step 1: Ensure all 4 wallets exist (creates buyer.asset / seller.cash if missing).
        Wallet buyerCash   = ensureWallet(buyer, cash, CASH_ASSET_ID);
        Wallet sellerAsset = ensureWallet(seller, asset, asset.getId());
        Wallet buyerAsset  = ensureWallet(buyer, asset, asset.getId());
        Wallet sellerCash  = ensureWallet(seller, cash, CASH_ASSET_ID);

        // Step 2: Lock all 4 in UUID-sorted order — prevents deadlock between concurrent settlements.
        Map<UUID, Wallet> locked = lockInOrder(List.of(
                buyerCash.getId(), sellerAsset.getId(), buyerAsset.getId(), sellerCash.getId()
        ));
        buyerCash   = locked.get(buyerCash.getId());
        sellerAsset = locked.get(sellerAsset.getId());
        buyerAsset  = locked.get(buyerAsset.getId());
        sellerCash  = locked.get(sellerCash.getId());

        // Step 3: Move money.
        buyerCash.settleDebit(totalCost);
        if (refund.signum() > 0) {
            buyerCash.releaseReservation(refund);
        }
        sellerAsset.settleDebit(qty);
        buyerAsset.credit(qty);
        sellerCash.credit(totalCost);

        walletRepository.save(buyerCash);
        walletRepository.save(sellerAsset);
        walletRepository.save(buyerAsset);
        walletRepository.save(sellerCash);

        // Cache invalidation events — fire AFTER_COMMIT via WalletEventListener.
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(buyer.getId(),  CASH_ASSET_ID));
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(seller.getId(), asset.getId()));
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(buyer.getId(),  asset.getId()));
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(seller.getId(), CASH_ASSET_ID));

        // Step 4: Persist the Trade.
        Trade trade = tradeRepository.save(Trade.builder()
                .buyOrder(buyOrder)
                .sellOrder(sellOrder)
                .buyer(buyer)
                .seller(seller)
                .asset(asset)
                .price(tradePrice)
                .quantity(qty)
                .totalAmount(totalCost)
                .build());

        // Step 5: Ledger — 4 rows, one per wallet movement.
        recordTx(buyerCash,   sellerCash,  TransactionType.DEBIT,  totalCost, buyerCash.getAvailableBalance(),   trade.getId());
        recordTx(sellerAsset, buyerAsset,  TransactionType.DEBIT,  qty,       sellerAsset.getAvailableBalance(), trade.getId());
        recordTx(buyerAsset,  sellerAsset, TransactionType.CREDIT, qty,       buyerAsset.getAvailableBalance(),  trade.getId());
        recordTx(sellerCash,  buyerCash,   TransactionType.CREDIT, totalCost, sellerCash.getAvailableBalance(),  trade.getId());

        // Step 6: Update both orders' fill counters / status.
        buyOrder.fill(qty);
        sellOrder.fill(qty);
        orderRepository.save(buyOrder);
        orderRepository.save(sellOrder);

        return trade;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void recordTx(Wallet wallet, Wallet counterparty, TransactionType type,
                          BigDecimal amount, BigDecimal balanceAfter, UUID tradeId) {
        transactionRepository.save(Transaction.builder()
                .wallet(wallet)
                .counterpartyWallet(counterparty)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .referenceId(tradeId)
                .referenceType("TRADE")
                .build());
    }

    private Wallet ensureWallet(User user, Asset asset, Integer assetId) {
        return walletRepository.findByUserIdAndAssetId(user.getId(), assetId)
                .orElseGet(() -> walletRepository.save(
                        Wallet.builder().user(user).asset(asset).build()
                ));
    }

    private Map<UUID, Wallet> lockInOrder(List<UUID> ids) {
        Map<UUID, Wallet> result = new HashMap<>();
        ids.stream().sorted().forEach(id ->
                result.put(id, walletRepository.findByIdWithLock(id)
                        .orElseThrow(() -> new IllegalStateException("Wallet vanished: " + id)))
        );
        return result;
    }
}
