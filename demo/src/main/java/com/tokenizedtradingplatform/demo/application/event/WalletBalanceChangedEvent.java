package com.tokenizedtradingplatform.demo.application.event;

import java.util.UUID;

/**
 * Published whenever a wallet's available or locked balance changes.
 * Handled by {@code WalletEventListener} AFTER_COMMIT to invalidate the
 * wallet + portfolio cache. Firing pre-commit would invalidate the cache
 * even on rollback, re-caching the (now stale) pre-rollback DB value on
 * the next read.
 */
public record WalletBalanceChangedEvent(UUID userId, Integer assetId) {}
