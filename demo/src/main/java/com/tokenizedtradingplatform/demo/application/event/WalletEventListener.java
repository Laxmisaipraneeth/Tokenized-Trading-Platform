package com.tokenizedtradingplatform.demo.application.event;

import com.tokenizedtradingplatform.demo.infrastructure.cache.PortfolioCacheAdapter;
import com.tokenizedtradingplatform.demo.infrastructure.cache.WalletCacheAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT — invalidates both the single-wallet cache and the
 * portfolio cache for the user whose balance changed. Running after
 * commit ensures we never delete a cache entry for a transaction that
 * then rolled back (which would let the next read re-cache stale data).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletEventListener {

    private final WalletCacheAdapter walletCache;
    private final PortfolioCacheAdapter portfolioCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBalanceChanged(WalletBalanceChangedEvent event) {
        log.debug("Cache invalidate: wallet {}/{} + portfolio {}",
                event.userId(), event.assetId(), event.userId());
        walletCache.invalidate(event.userId(), event.assetId());
        portfolioCache.invalidate(event.userId());
    }
}
