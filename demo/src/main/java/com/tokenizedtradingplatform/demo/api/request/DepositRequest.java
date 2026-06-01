package com.tokenizedtradingplatform.demo.api.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.00000001", message = "Deposit amount must be positive")
    private BigDecimal amount;
}
