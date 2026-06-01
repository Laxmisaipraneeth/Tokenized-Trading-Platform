package com.tokenizedtradingplatform.demo.application.service;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import com.tokenizedtradingplatform.demo.domain.enums.OrderStatus;
import com.tokenizedtradingplatform.demo.domain.model.Order;
import com.tokenizedtradingplatform.demo.domain.model.Trade;
import com.tokenizedtradingplatform.demo.domain.repository.OrderRepository;
import com.tokenizedtradingplatform.demo.infrastructure.OrderBookRedisAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Price-time priority matching loop. Pulls best opposing orders from Redis,
 * settles fills via {@link TradeSettlementService}, removes filled orders
 * from the Redis book.
 *
 * Runs in the caller's transaction (settle is REQUIRED) so a mid-match
 * failure rolls back every fill cleanly.
 */
@Service
@RequiredArgsConstructor
public class MatchingEngine {

    private final OrderRepository orderRepository;
    private final OrderBookRedisAdapter orderBook;
    private final TradeSettlementService settlementService;

    public List<Trade> match(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        String symbol = incoming.getAsset().getSymbol();
        OrderSide oppositeSide = incoming.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;

        while (incoming.getRemainingQuantity().signum() > 0) {
            String bestOpposingId = (incoming.getSide() == OrderSide.BUY)
                    ? orderBook.getBestAsk(symbol)
                    : orderBook.getBestBid(symbol);
            if (bestOpposingId == null) break;

            Order opposing = orderRepository.findByIdWithLock(UUID.fromString(bestOpposingId)).orElse(null);

            // Stale Redis entry — order no longer matchable. Clean up and continue.
            if (opposing == null
                    || opposing.getStatus() == OrderStatus.CANCELLED
                    || opposing.getStatus() == OrderStatus.FILLED
                    || opposing.getRemainingQuantity().signum() == 0) {
                orderBook.removeOrder(symbol, oppositeSide, UUID.fromString(bestOpposingId));
                continue;
            }

            // Price-cross check.
            if (incoming.getSide() == OrderSide.BUY
                    && opposing.getPrice().compareTo(incoming.getPrice()) > 0) break;
            if (incoming.getSide() == OrderSide.SELL
                    && opposing.getPrice().compareTo(incoming.getPrice()) < 0) break;

            BigDecimal fillQty = incoming.getRemainingQuantity().min(opposing.getRemainingQuantity());
            BigDecimal tradePrice = opposing.getPrice(); // resting order sets price.

            Order buyOrder  = incoming.getSide() == OrderSide.BUY ? incoming : opposing;
            Order sellOrder = incoming.getSide() == OrderSide.BUY ? opposing : incoming;

            Trade trade = settlementService.settle(buyOrder, sellOrder, tradePrice, fillQty);
            trades.add(trade);

            // Settlement already called incoming.fill() and opposing.fill().
            // If opposing is now fully filled, remove from Redis sorted set.
            if (opposing.getStatus() == OrderStatus.FILLED) {
                orderBook.removeOrder(symbol, opposing.getSide(), opposing.getId());
            }
        }

        return trades;
    }
}
