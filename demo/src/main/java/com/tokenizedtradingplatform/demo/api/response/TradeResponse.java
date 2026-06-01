package com.tokenizedtradingplatform.demo.api.response;

import com.tokenizedtradingplatform.demo.domain.model.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeResponse(
        UUID id,
        UUID buyOrderId,
        UUID sellOrderId,
        UUID buyerId,
        UUID sellerId,
        String assetSymbol,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal totalAmount,
        Instant executedAt
) {
    public static TradeResponse from(Trade t) {
        return new TradeResponse(
                t.getId(),
                t.getBuyOrder().getId(),
                t.getSellOrder().getId(),
                t.getBuyer().getId(),
                t.getSeller().getId(),
                t.getAsset().getSymbol(),
                t.getPrice(),
                t.getQuantity(),
                t.getTotalAmount(),
                t.getExecutedAt()
        );
    }
}
