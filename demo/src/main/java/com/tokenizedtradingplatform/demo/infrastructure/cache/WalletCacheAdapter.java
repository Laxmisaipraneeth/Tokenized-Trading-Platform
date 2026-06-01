package com.tokenizedtradingplatform.demo.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenizedtradingplatform.demo.api.response.WalletResponse;
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
 * Redis cache-aside for {@link WalletResponse}, keyed by user+asset.
 *
 *   Key:   wallet:balance:{userId}:{assetId}
 *   Value: JSON string (serialized via Spring's autoconfigured ObjectMapper)
 *   TTL:   app.cache.wallet-ttl-seconds (default 30s)
 *
 * Stores plain JSON strings rather than letting Redisson handle the codec
 * — sidesteps Redisson's mandatory polymorphic type tags which clash with
 * nested generic types (e.g. List in PortfolioResponse).
 *
 * Invalidation happens in WalletEventListener AFTER_COMMIT so rollback
 * never leaves stale data.
 *
 * Metrics: wallet.cache.hit / wallet.cache.miss.
 */
@Component
@Slf4j
public class WalletCacheAdapter {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final Counter hitCounter;
    private final Counter missCounter;

    public WalletCacheAdapter(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.cache.wallet-ttl-seconds:30}") long ttlSeconds) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.hitCounter = Counter.builder("wallet.cache.hit")
                .description("Wallet balance cache hits")
                .register(meterRegistry);
        this.missCounter = Counter.builder("wallet.cache.miss")
                .description("Wallet balance cache misses")
                .register(meterRegistry);
    }

    public Optional<WalletResponse> get(UUID userId, Integer assetId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId, assetId));
            String json = bucket.get();
            if (json != null) {
                hitCounter.increment();
                return Optional.of(objectMapper.readValue(json, WalletResponse.class));
            }
            missCounter.increment();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Cache read failed for {}/{}: {}", userId, assetId, e.getMessage());
            missCounter.increment();
            return Optional.empty();
        }
    }

    public void put(UUID userId, Integer assetId, WalletResponse value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redissonClient.<String>getBucket(key(userId, assetId))
                    .set(json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Cache write serialize failed for {}/{}: {}", userId, assetId, e.getMessage());
        } catch (Exception e) {
            log.warn("Cache write failed for {}/{}: {}", userId, assetId, e.getMessage());
        }
    }

    public void invalidate(UUID userId, Integer assetId) {
        try {
            redissonClient.getBucket(key(userId, assetId)).delete();
        } catch (Exception e) {
            log.warn("Cache invalidate failed for {}/{}: {}", userId, assetId, e.getMessage());
        }
    }

    private String key(UUID userId, Integer assetId) {
        return "wallet:balance:" + userId + ":" + assetId;
    }
}
