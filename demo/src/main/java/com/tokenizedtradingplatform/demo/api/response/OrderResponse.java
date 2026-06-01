package com.tokenizedtradingplatform.demo.api.response;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import com.tokenizedtradingplatform.demo.domain.enums.OrderStatus;
import com.tokenizedtradingplatform.demo.domain.enums.OrderType;
import com.tokenizedtradingplatform.demo.domain.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String assetSymbol,
        OrderSide side,
        OrderType type,
        OrderStatus status,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        Instant createdAt
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(),
                o.getAsset().getSymbol(),
                o.getSide(),
                o.getType(),
                o.getStatus(),
                o.getPrice(),
                o.getQuantity(),
                o.getFilledQuantity(),
                o.getRemainingQuantity(),
                o.getCreatedAt()
        );
    }
}
