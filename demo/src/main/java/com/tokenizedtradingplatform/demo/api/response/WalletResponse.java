package com.tokenizedtradingplatform.demo.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class WalletResponse {
    private UUID id;
    private String assetSymbol;
    private String assetName;
    private BigDecimal availableBalance;
    private BigDecimal lockedBalance;
    private BigDecimal totalBalance;
}
