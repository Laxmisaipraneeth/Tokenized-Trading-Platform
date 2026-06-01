package com.tokenizedtradingplatform.demo.application.service;

import com.tokenizedtradingplatform.demo.api.request.DepositRequest;
import com.tokenizedtradingplatform.demo.api.request.WithdrawRequest;
import com.tokenizedtradingplatform.demo.api.response.TransactionResponse;
import com.tokenizedtradingplatform.demo.api.response.WalletResponse;
import com.tokenizedtradingplatform.demo.application.event.WalletBalanceChangedEvent;
import com.tokenizedtradingplatform.demo.domain.enums.TransactionType;
import com.tokenizedtradingplatform.demo.domain.model.Asset;
import com.tokenizedtradingplatform.demo.domain.model.Transaction;
import com.tokenizedtradingplatform.demo.domain.model.User;
import com.tokenizedtradingplatform.demo.domain.model.Wallet;
import com.tokenizedtradingplatform.demo.domain.repository.AssetRepository;
import com.tokenizedtradingplatform.demo.domain.repository.TransactionRepository;
import com.tokenizedtradingplatform.demo.domain.repository.UserRepository;
import com.tokenizedtradingplatform.demo.domain.repository.WalletRepository;
import com.tokenizedtradingplatform.demo.exception.ResourceNotFoundException;
import com.tokenizedtradingplatform.demo.infrastructure.cache.WalletCacheAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final TransactionRepository transactionRepository;
    private final WalletCacheAdapter walletCache;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public WalletResponse getOrCreateWallet(UUID userId, Integer assetId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset not found: " + assetId));

        Wallet wallet = walletRepository.findByUserIdAndAssetId(userId, assetId)
                .orElseGet(() -> walletRepository.save(
                        Wallet.builder().user(user).asset(asset).build()
                ));

        return toResponse(wallet);
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> getAllWallets(UUID userId) {
        return walletRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID userId, Integer assetId) {
        return walletCache.get(userId, assetId).orElseGet(() -> {
            Wallet wallet = walletRepository.findByUserIdAndAssetId(userId, assetId)
                    .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for asset: " + assetId));
            WalletResponse response = toResponse(wallet);
            walletCache.put(userId, assetId, response);
            return response;
        });
    }

    @Transactional
    public WalletResponse deposit(UUID userId, Integer assetId, DepositRequest request) {
        Wallet wallet = walletRepository.findByUserIdAndAssetIdWithLock(userId, assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found — create it first"));

        wallet.credit(request.getAmount());
        wallet = walletRepository.save(wallet);

        recordTransaction(wallet, null, TransactionType.CREDIT, request.getAmount(),
                wallet.getAvailableBalance(), null, "DEPOSIT");

        eventPublisher.publishEvent(new WalletBalanceChangedEvent(userId, assetId));
        return toResponse(wallet);
    }

    @Transactional
    public WalletResponse withdraw(UUID userId, Integer assetId, WithdrawRequest request) {
        Wallet wallet = walletRepository.findByUserIdAndAssetIdWithLock(userId, assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found — create it first"));

        wallet.debit(request.getAmount());
        wallet = walletRepository.save(wallet);

        recordTransaction(wallet, null, TransactionType.DEBIT, request.getAmount(),
                wallet.getAvailableBalance(), null, "WITHDRAWAL");

        eventPublisher.publishEvent(new WalletBalanceChangedEvent(userId, assetId));
        return toResponse(wallet);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactionHistory(UUID userId, Integer assetId, Pageable pageable) {
        Wallet wallet = walletRepository.findByUserIdAndAssetId(userId, assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for asset: " + assetId));

        return transactionRepository.findByWalletId(wallet.getId(), pageable)
                .map(this::toTransactionResponse);
    }

    // ── Internal helpers (used by TradeSettlementService in Phase 3) ──────────

    public void recordTransaction(Wallet wallet, Wallet counterparty, TransactionType type,
                                  BigDecimal amount, BigDecimal balanceAfter,
                                  UUID referenceId, String referenceType) {
        transactionRepository.save(Transaction.builder()
                .wallet(wallet)
                .counterpartyWallet(counterparty)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .referenceId(referenceId)
                .referenceType(referenceType)
                .build());
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getAsset().getSymbol(),
                wallet.getAsset().getName(),
                wallet.getAvailableBalance(),
                wallet.getLockedBalance(),
                wallet.getTotalBalance()
        );
    }

    private TransactionResponse toTransactionResponse(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getType(),
                t.getAmount(),
                t.getBalanceAfter(),
                t.getReferenceType(),
                t.getCreatedAt()
        );
    }
}
