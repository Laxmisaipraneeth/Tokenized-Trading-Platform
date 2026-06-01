package com.tokenizedtradingplatform.demo.infrastructure.lock;

import com.tokenizedtradingplatform.demo.exception.LockAcquisitionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around Redisson RLock. Used directly by code that needs
 * fine-grained lock control, and by {@link DistributedLockAspect} which
 * applies it declaratively via {@link DistributedLock}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private final RedissonClient redissonClient;

    /**
     * Acquire a lock or throw {@link LockAcquisitionException} (→ 409).
     * Lease auto-expires so a crashed holder doesn't deadlock the system.
     */
    public RLock acquire(String key, long waitSeconds, long leaseSeconds) {
        RLock lock = redissonClient.getLock(key);
        try {
            boolean acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Lock acquisition timed out: {}", key);
                throw new LockAcquisitionException(key);
            }
            log.debug("Lock acquired: {}", key);
            return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(key);
        }
    }

    /** Safe release — no-op if we don't hold the lock anymore (lease expired). */
    public void release(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("Lock released: {}", lock.getName());
        }
    }
}
