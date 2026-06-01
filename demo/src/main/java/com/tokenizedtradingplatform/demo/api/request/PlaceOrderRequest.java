package com.tokenizedtradingplatform.demo.api.request;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PlaceOrderRequest(

        @NotNull(message = "assetId is required")
        Integer assetId,

        @NotNull(message = "side is required")
        OrderSide side,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.00000001", message = "price must be positive")
        BigDecimal price,

        @NotNull(message = "quantity is required")
        @DecimalMin(value = "0.00000001", message = "quantity must be positive")
        BigDecimal quantity
) {}
