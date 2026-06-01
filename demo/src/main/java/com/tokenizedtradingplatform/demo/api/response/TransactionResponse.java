package com.tokenizedtradingplatform.demo.api.response;

import com.tokenizedtradingplatform.demo.domain.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
public class TransactionResponse {
    private UUID id;
    private TransactionType type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String referenceType;
    private LocalDateTime createdAt;
}
