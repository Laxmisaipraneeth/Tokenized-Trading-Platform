package com.tokenizedtradingplatform.demo.application.service;

import com.tokenizedtradingplatform.demo.api.request.PlaceOrderRequest;
import com.tokenizedtradingplatform.demo.api.response.OrderBookResponse;
import com.tokenizedtradingplatform.demo.api.response.OrderResponse;
import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import com.tokenizedtradingplatform.demo.domain.enums.OrderStatus;
import com.tokenizedtradingplatform.demo.domain.model.Asset;
import com.tokenizedtradingplatform.demo.domain.model.Order;
import com.tokenizedtradingplatform.demo.domain.model.User;
import com.tokenizedtradingplatform.demo.domain.model.Wallet;
import com.tokenizedtradingplatform.demo.domain.repository.AssetRepository;
import com.tokenizedtradingplatform.demo.domain.repository.OrderRepository;
import com.tokenizedtradingplatform.demo.domain.repository.UserRepository;
import com.tokenizedtradingplatform.demo.domain.repository.WalletRepository;
import com.tokenizedtradingplatform.demo.application.event.WalletBalanceChangedEvent;
import com.tokenizedtradingplatform.demo.exception.InsufficientFundsException;
import com.tokenizedtradingplatform.demo.exception.ResourceNotFoundException;
import com.tokenizedtradingplatform.demo.infrastructure.OrderBookRedisAdapter;
import com.tokenizedtradingplatform.demo.infrastructure.lock.DistributedLock;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.redisson.client.protocol.ScoredEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int CASH_ASSET_ID = 1;
    private static final int ORDER_BOOK_DEPTH = 20;

    private final OrderRepository orderRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final OrderBookRedisAdapter orderBook;
    private final TradeExecutionService tradeExecutionService;
    private final ApplicationEventPublisher eventPublisher;

    @DistributedLock(key = "lock:trade:execute:#{#userId}")
    @Transactional
    public OrderResponse placeOrder(UUID userId, PlaceOrderRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Asset asset = assetRepository.findById(req.assetId())
                .orElseThrow(() -> new ResourceNotFoundException("Asset not found: " + req.assetId()));

        if (!asset.isTradeable()) {
            throw new IllegalArgumentException("Asset is not tradeable: " + asset.getSymbol());
        }

        BigDecimal reserveAmount = computeReserveAmount(req);
        Integer reserveAssetId = req.side() == OrderSide.BUY ? CASH_ASSET_ID : req.assetId();

        Wallet reserveWallet = walletRepository.findByUserIdAndAssetIdWithLock(userId, reserveAssetId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wallet not found for asset: " + reserveAssetId + " — create and fund it first"));

        if (reserveWallet.getAvailableBalance().compareTo(reserveAmount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient balance. Required: " + reserveAmount +
                    ", Available: " + reserveWallet.getAvailableBalance());
        }

        reserveWallet.reserve(reserveAmount);
        walletRepository.save(reserveWallet);
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(userId, reserveAssetId));

        Order order = orderRepository.saveAndFlush(Order.builder()
                .user(user)
                .asset(asset)
                .side(req.side())
                .price(req.price())
                .quantity(req.quantity())
                .build());

        // Run matching against the existing book; any unfilled remainder is rested in Redis.
        tradeExecutionService.execute(order);

        return OrderResponse.from(order);
    }

    @DistributedLock(key = "lock:trade:execute:#{#userId}")
    @Transactional
    public OrderResponse cancelOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }

        if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Order cannot be cancelled — status: " + order.getStatus());
        }

        orderBook.removeOrder(order.getAsset().getSymbol(), order.getSide(), orderId);

        BigDecimal releaseAmount = computeReleaseAmount(order);
        Integer releaseAssetId = order.getSide() == OrderSide.BUY ? CASH_ASSET_ID : order.getAsset().getId();

        Wallet wallet = walletRepository.findByUserIdAndAssetIdWithLock(userId, releaseAssetId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        wallet.releaseReservation(releaseAmount);
        walletRepository.save(wallet);
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(userId, releaseAssetId));

        order.cancel();
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getUserOrders(UUID userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOpenOrders(UUID userId) {
        return orderRepository.findByUserIdAndStatusIn(
                userId, List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED))
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderBookResponse getOrderBook(String symbol) {
        assetRepository.findBySymbol(symbol)
                .orElseThrow(() -> new ResourceNotFoundException("Asset not found: " + symbol));

        Collection<ScoredEntry<String>> bidEntries = orderBook.getBestBids(symbol, ORDER_BOOK_DEPTH);
        Collection<ScoredEntry<String>> askEntries = orderBook.getBestAsks(symbol, ORDER_BOOK_DEPTH);

        List<OrderBookResponse.Level> bids = aggregateLevels(bidEntries, OrderSide.BUY);
        List<OrderBookResponse.Level> asks = aggregateLevels(askEntries, OrderSide.SELL);

        return new OrderBookResponse(symbol, bids, asks);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal computeReserveAmount(PlaceOrderRequest req) {
        return req.side() == OrderSide.BUY
                ? req.price().multiply(req.quantity())
                : req.quantity();
    }

    private BigDecimal computeReleaseAmount(Order order) {
        BigDecimal remaining = order.getRemainingQuantity();
        return order.getSide() == OrderSide.BUY
                ? order.getPrice().multiply(remaining)
                : remaining;
    }

    private List<OrderBookResponse.Level> aggregateLevels(
            Collection<ScoredEntry<String>> entries, OrderSide side) {

        Map<BigDecimal, BigDecimal[]> map = new LinkedHashMap<>();

        for (ScoredEntry<String> entry : entries) {
            UUID orderId = UUID.fromString(entry.getValue());
            BigDecimal price = orderBook.recoverPrice(side, entry.getScore());

            orderRepository.findById(orderId).ifPresent(o -> {
                BigDecimal[] agg = map.computeIfAbsent(price, p -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                agg[0] = agg[0].add(o.getRemainingQuantity());
                agg[1] = agg[1].add(BigDecimal.ONE);
            });
        }

        return map.entrySet().stream()
                .map(e -> new OrderBookResponse.Level(e.getKey(), e.getValue()[0], e.getValue()[1].intValue()))
                .toList();
    }
}
