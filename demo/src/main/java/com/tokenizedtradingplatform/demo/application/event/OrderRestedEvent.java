package com.tokenizedtradingplatform.demo.application.event;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published when an order has remaining quantity after matching and needs
 * to rest in the order book. Listener fires the Redis ZADD AFTER_COMMIT,
 * so a transaction rollback never leaves a phantom resting order.
 */
public record OrderRestedEvent(
        String symbol,
        OrderSide side,
        BigDecimal price,
        UUID orderId
) {}
