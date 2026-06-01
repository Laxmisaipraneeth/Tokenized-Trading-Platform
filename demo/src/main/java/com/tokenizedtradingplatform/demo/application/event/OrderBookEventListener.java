package com.tokenizedtradingplatform.demo.application.event;

import com.tokenizedtradingplatform.demo.infrastructure.OrderBookRedisAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT — adds a resting order to the Redis sorted set only once
 * the DB transaction has committed. If the placement rolls back (e.g.,
 * settlement failure on a partial fill), the order is never published
 * to the book, so no client can pick it up.
 *
 * Mid-matching ZREM operations stay direct (in MatchingEngine) — the loop
 * needs Redis to reflect filled orders mid-iteration to make progress.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderBookEventListener {

    private final OrderBookRedisAdapter orderBook;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderRested(OrderRestedEvent event) {
        log.debug("Order rested: {}:{} {} @ {} (orderId={})",
                event.symbol(), event.side(), event.price(), event.price(), event.orderId());
        orderBook.addOrder(event.symbol(), event.side(), event.price(), event.orderId());
    }
}
