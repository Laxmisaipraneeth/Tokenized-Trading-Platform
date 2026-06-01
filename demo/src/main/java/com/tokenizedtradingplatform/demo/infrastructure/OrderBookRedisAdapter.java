package com.tokenizedtradingplatform.demo.infrastructure;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderBookRedisAdapter {

    private static final long PRICE_MULTIPLIER = 1_000_000L;

    private final RedissonClient redissonClient;

    // BUY  — negative score so highest bid is first in ascending ZRANGE
    // SELL — positive score so lowest ask is first in ascending ZRANGE
    private double toScore(OrderSide side, BigDecimal price) {
        long raw = price.multiply(BigDecimal.valueOf(PRICE_MULTIPLIER)).longValue();
        return side == OrderSide.BUY ? -raw : raw;
    }

    private String bidsKey(String symbol) { return "orderbook:" + symbol + ":bids"; }
    private String asksKey(String symbol) { return "orderbook:" + symbol + ":asks"; }

    private String key(String symbol, OrderSide side) {
        return side == OrderSide.BUY ? bidsKey(symbol) : asksKey(symbol);
    }

    public void addOrder(String symbol, OrderSide side, BigDecimal price, UUID orderId) {
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key(symbol, side));
        zset.add(toScore(side, price), orderId.toString());
    }

    public void removeOrder(String symbol, OrderSide side, UUID orderId) {
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key(symbol, side));
        zset.remove(orderId.toString());
    }

    // Returns best bids (highest price first) — top N entries
    public Collection<ScoredEntry<String>> getBestBids(String symbol, int limit) {
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(bidsKey(symbol));
        return zset.entryRange(0, limit - 1);
    }

    // Returns best asks (lowest price first) — top N entries
    public Collection<ScoredEntry<String>> getBestAsks(String symbol, int limit) {
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(asksKey(symbol));
        return zset.entryRange(0, limit - 1);
    }

    // Used by matching engine — peek at best opposing order
    public String getBestAsk(String symbol) {
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(asksKey(symbol));
        return zset.first();
    }

    public String getBestBid(String symbol) {
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(bidsKey(symbol));
        return zset.first();
    }

    public BigDecimal recoverPrice(OrderSide side, double score) {
        long raw = (long) (side == OrderSide.BUY ? -score : score);
        return BigDecimal.valueOf(raw, 6);
    }
}
