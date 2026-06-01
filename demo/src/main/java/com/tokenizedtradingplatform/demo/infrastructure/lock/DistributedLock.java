package com.tokenizedtradingplatform.demo.infrastructure.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wraps the annotated method in a Redisson distributed lock acquired before
 * the method runs and released after it returns.
 *
 * The {@code key} is a Spring SpEL template (delimiters {@code #{ ... }}).
 * Method parameter names are available as variables, e.g.:
 *
 * <pre>{@code
 *   @DistributedLock(key = "lock:trade:execute:#{#userId}")
 *   public OrderResponse placeOrder(UUID userId, PlaceOrderRequest req) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /** SpEL template expression that evaluates to the Redis lock key. */
    String key();

    /** Max time to wait for the lock before giving up (→ 409). */
    long waitSeconds() default 3;

    /** Lease — auto-expires so a crashed holder doesn't hang the system. */
    long leaseSeconds() default 10;
}
