package com.tokenizedtradingplatform.demo.application.service;

import com.tokenizedtradingplatform.demo.api.response.TradeResponse;
import com.tokenizedtradingplatform.demo.application.event.OrderRestedEvent;
import com.tokenizedtradingplatform.demo.domain.model.Order;
import com.tokenizedtradingplatform.demo.domain.model.Trade;
import com.tokenizedtradingplatform.demo.domain.repository.OrderRepository;
import com.tokenizedtradingplatform.demo.domain.repository.TradeRepository;
import com.tokenizedtradingplatform.demo.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Order placement orchestrator: runs matching against the existing book,
 * then rests any unfilled remainder in Redis.
 *
 * Called by OrderService.placeOrder() after the order is persisted and the
 * caller's balance has been reserved.
 */
@Service
@RequiredArgsConstructor
public class TradeExecutionService {

    private final MatchingEngine matchingEngine;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Match an incoming order against the existing book. Returns the trades that fired
     * (possibly empty). The incoming order's in-memory state reflects all fills.
     *
     * If the incoming order has remaining quantity after matching, an {@link OrderRestedEvent}
     * is published — the {@code OrderBookEventListener} adds it to Redis AFTER_COMMIT so a
     * rollback never leaves a phantom resting order.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<Trade> execute(Order incoming) {
        List<Trade> trades = matchingEngine.match(incoming);

        if (incoming.getRemainingQuantity().signum() > 0) {
            eventPublisher.publishEvent(new OrderRestedEvent(
                    incoming.getAsset().getSymbol(),
                    incoming.getSide(),
                    incoming.getPrice(),
                    incoming.getId()
            ));
        }

        return trades;
    }

    @Transactional(readOnly = true)
    public Page<TradeResponse> getUserTrades(UUID userId, Pageable pageable) {
        return tradeRepository.findByUserId(userId, pageable).map(TradeResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<TradeResponse> getAssetTrades(Integer assetId, Pageable pageable) {
        return tradeRepository.findByAssetId(assetId, pageable).map(TradeResponse::from);
    }

    @Transactional(readOnly = true)
    public List<TradeResponse> getOrderTrades(UUID userId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
        return tradeRepository.findByOrderId(orderId).stream()
                .map(TradeResponse::from)
                .toList();
    }
}
