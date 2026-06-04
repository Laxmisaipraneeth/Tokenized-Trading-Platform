package com.tokenizedtradingplatform.demo.invariants;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #1 — Wealth conservation.
 *
 * A trade is a transfer: cash leaves the buyer and arrives at the seller; the asset leaves
 * the seller and arrives at the buyer. The system must never mint or burn value. So the
 * total supply of every asset (summed available + locked over all wallets) is identical
 * before and after any number of trades.
 */
@DisplayName("Wealth conservation: trades transfer value, never create or destroy it")
class WealthConservationTest extends AbstractFinancialInvariantTest {

    @Test
    @DisplayName("a fully-matched trade leaves total CASH and total GOLD supply unchanged")
    void fullMatchConservesTotalSupply() {
        UUID buyer = newUser("buyer@test.io");
        UUID seller = newUser("seller@test.io");

        fund(buyer, CASH, "100000");
        fund(seller, GOLD, "100");

        BigDecimal cashBefore = totalSupply(CASH);
        BigDecimal goldBefore = totalSupply(GOLD);

        // Seller rests 100 GOLD @ 100; buyer crosses it in full.
        place(seller, OrderSide.SELL, GOLD, "100", "100");
        place(buyer, OrderSide.BUY, GOLD, "100", "100");

        // Supply is conserved to the cent / satoshi.
        assertThat(totalSupply(CASH)).isEqualByComparingTo(cashBefore);
        assertThat(totalSupply(GOLD)).isEqualByComparingTo(goldBefore);

        // And the value actually moved: buyer holds the gold, seller holds the cash.
        assertThat(balance(buyer, GOLD)).isEqualByComparingTo("100");
        assertThat(balance(seller, GOLD)).isEqualByComparingTo("0");
        assertThat(balance(seller, CASH)).isEqualByComparingTo("10000");
        assertThat(balance(buyer, CASH)).isEqualByComparingTo("90000");

        assertThat(negativeBalanceCount()).isZero();
    }

    @Test
    @DisplayName("a chain of partial fills across three traders still conserves total supply")
    void partialFillsConserveTotalSupply() {
        UUID seller = newUser("seller@test.io");
        UUID buyer1 = newUser("buyer1@test.io");
        UUID buyer2 = newUser("buyer2@test.io");

        fund(seller, GOLD, "30");
        fund(buyer1, CASH, "5000");
        fund(buyer2, CASH, "5000");

        BigDecimal cashBefore = totalSupply(CASH);
        BigDecimal goldBefore = totalSupply(GOLD);

        // 30 GOLD offered, consumed by two buyers at the resting price of 50.
        place(seller, OrderSide.SELL, GOLD, "50", "30");
        place(buyer1, OrderSide.BUY, GOLD, "50", "20");
        place(buyer2, OrderSide.BUY, GOLD, "50", "10");

        assertThat(totalSupply(CASH)).isEqualByComparingTo(cashBefore);
        assertThat(totalSupply(GOLD)).isEqualByComparingTo(goldBefore);

        assertThat(balance(buyer1, GOLD)).isEqualByComparingTo("20");
        assertThat(balance(buyer2, GOLD)).isEqualByComparingTo("10");
        assertThat(balance(seller, GOLD)).isEqualByComparingTo("0");
        assertThat(balance(seller, CASH)).isEqualByComparingTo("1500"); // 20*50 + 10*50

        assertThat(negativeBalanceCount()).isZero();
    }
}
