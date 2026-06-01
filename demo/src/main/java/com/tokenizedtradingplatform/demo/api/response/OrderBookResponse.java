package com.tokenizedtradingplatform.demo.api.response;

import java.math.BigDecimal;
import java.util.List;

public record OrderBookResponse(
        String symbol,
        List<Level> bids,
        List<Level> asks
) {
    public record Level(BigDecimal price, BigDecimal quantity, int orderCount) {}
}
