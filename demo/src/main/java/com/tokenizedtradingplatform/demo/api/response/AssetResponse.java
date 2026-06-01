package com.tokenizedtradingplatform.demo.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AssetResponse {
    private Integer id;
    private String symbol;
    private String name;
    private int decimalPlaces;
    private boolean tradeable;
}
