package com.tokenizedtradingplatform.demo.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenizedtradingplatform.demo.api.response.PortfolioResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 *   Key:   portfolio:{userId}
 *   Value: JSON string of PortfolioResponse
 *   TTL:   app.cache.portfolio-ttl-seconds (default 60s)
 *
 * Invalidated whenever ANY wallet for the user changes (see WalletEventListener).
 */
@Component
@Slf4j
public class PortfolioCacheAdapter {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final Counter hitCounter;
    private final Counter missCounter;

    public PortfolioCacheAdapter(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.cache.portfolio-ttl-seconds:60}") long ttlSeconds) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.hitCounter = Counter.builder("portfolio.cache.hit").register(meterRegistry);
        this.missCounter = Counter.builder("portfolio.cache.miss").register(meterRegistry);
    }

    public Optional<PortfolioResponse> get(UUID userId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId));
            String json = bucket.get();
            if (json != null) {
                hitCounter.increment();
                return Optional.of(objectMapper.readValue(json, PortfolioResponse.class));
            }
            missCounter.increment();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Portfolio cache read failed for {}: {}", userId, e.getMessage());
            missCounter.increment();
            return Optional.empty();
        }
    }

    public void put(UUID userId, PortfolioResponse value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redissonClient.<String>getBucket(key(userId))
                    .set(json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Portfolio cache serialize failed for {}: {}", userId, e.getMessage());
        } catch (Exception e) {
            log.warn("Portfolio cache write failed for {}: {}", userId, e.getMessage());
        }
    }

    public void invalidate(UUID userId) {
        try {
            redissonClient.getBucket(key(userId)).delete();
        } catch (Exception e) {
            log.warn("Portfolio cache invalidate failed for {}: {}", userId, e.getMessage());
        }
    }

    private String key(UUID userId) {
        return "portfolio:" + userId;
    }
}
