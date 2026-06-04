package com.tokenizedtradingplatform.demo.invariants;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #2 — No double-spend under concurrency.
 *
 * One seller rests exactly 10 GOLD. Many buyers each try to take all 10 at the same instant.
 * The asset is scarce: at most 10 GOLD can ever change hands. The protection under test is the
 * pessimistic row lock + stale-status check in MatchingEngine — concurrent matchers race for the
 * same resting sell order, but the first to win the lock fills it and the rest must observe it as
 * FILLED and skip. The seller must never deliver more than the 10 GOLD they actually hold.
 */
@DisplayName("No double-spend: concurrent buyers cannot over-fill one resting sell order")
class DoubleSpendConcurrencyTest extends AbstractFinancialInvariantTest {

    private static final int BUYERS = 8;

    @Test
    @DisplayName("8 buyers concurrently hit a 10-GOLD ask; exactly 10 GOLD is sold, no more")
    void concurrentBuyersCannotOversell() throws InterruptedException {
        UUID seller = newUser("seller@test.io");
        fund(seller, GOLD, "10");

        // Rest the only liquidity: 10 GOLD @ 100. placeOrder commits synchronously,
        // so when it returns the ask is already in the Redis book.
        place(seller, OrderSide.SELL, GOLD, "100", "10");

        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            UUID b = newUser("buyer" + i + "@test.io");
            fund(b, CASH, "2000"); // enough to reserve 10*100 = 1000
            buyers.add(b);
        }

        BigDecimal goldBefore = totalSupply(GOLD);

        // Fire all buyers at once — each wants the full 10 GOLD @ 100.
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        CountDownLatch ready = new CountDownLatch(BUYERS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(BUYERS);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (UUID b : buyers) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    place(b, OrderSide.BUY, GOLD, "100", "10");
                } catch (Throwable t) {
                    unexpected.set(t); // any thrown exception here is a real failure
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("all buyer threads finished").isTrue();
        pool.shutdownNow();

        assertThat(unexpected.get()).as("no buyer thread threw").isNull();

        // ── The core double-spend assertions ────────────────────────────────────
        // Supply of GOLD is unchanged — nothing was minted by the race.
        assertThat(totalSupply(GOLD)).isEqualByComparingTo(goldBefore);

        // The seller delivered EXACTLY the 10 GOLD they held — not 20, not 80.
        assertThat(balance(seller, GOLD)).isEqualByComparingTo("0");
        BigDecimal goldDeliveredToBuyers = buyers.stream()
                .map(b -> balance(b, GOLD))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(goldDeliveredToBuyers).isEqualByComparingTo("10");

        // The seller was paid for exactly 10 GOLD @ 100 — once.
        assertThat(balance(seller, CASH)).isEqualByComparingTo("1000");

        // Exactly one buyer won the whole lot; the others got nothing (their bids rest).
        long winners = buyers.stream()
                .filter(b -> balance(b, GOLD).compareTo(BigDecimal.ZERO) > 0)
                .count();
        assertThat(winners).isEqualTo(1);

        // The sell order is recorded as fully filled, never beyond its quantity.
        BigDecimal sellFilled = jdbc.queryForObject(
                "SELECT filled_quantity FROM orders WHERE side = 'SELL'", BigDecimal.class);
        assertThat(sellFilled).isEqualByComparingTo("10");

        // No invariant-violating balances anywhere.
        assertThat(negativeBalanceCount()).isZero();
    }
}
