package com.tokenizedtradingplatform.demo.invariants;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant #3 — Ledger reconciliation.
 *
 * The transaction table is the immutable double-entry ledger. Two things must always hold:
 *
 *  (a) Per-wallet reconciliation: a wallet's total balance (available + locked) equals the
 *      signed sum of its ledger rows, Σ(CREDIT) − Σ(DEBIT). Deposits, withdrawals, credits and
 *      settle-debits all post a row; reserve/release only shuffle available↔locked within the
 *      same wallet (no value change), so the ledger and the wallet can never drift apart.
 *
 *  (b) Per-trade double-entry: every trade posts exactly 4 rows, and within each asset the
 *      debited amount equals the credited amount — value conserved on every single trade.
 */
@DisplayName("Ledger reconciliation: the transaction ledger always reconciles to wallet state")
class LedgerReconciliationTest extends AbstractFinancialInvariantTest {

    @Test
    @DisplayName("after deposits and a chain of partial-fill trades, every wallet reconciles to its ledger")
    void everyWalletReconcilesToItsLedger() {
        UUID seller = newUser("seller@test.io");
        UUID buyer1 = newUser("buyer1@test.io");
        UUID buyer2 = newUser("buyer2@test.io");

        fund(seller, GOLD, "10");
        fund(buyer1, CASH, "1000");
        fund(buyer2, CASH, "1000");

        // 10 GOLD @ 100, consumed by a 4-lot and then a 6-lot — two trades, one of them
        // completing the resting order. Exercises reserve, settle-debit, refund and credit.
        place(seller, OrderSide.SELL, GOLD, "100", "10");
        place(buyer1, OrderSide.BUY, GOLD, "100", "4");
        place(buyer2, OrderSide.BUY, GOLD, "100", "6");

        // (a) Every wallet: total balance == Σ(CREDIT) − Σ(DEBIT) over its own ledger rows.
        List<Map<String, Object>> wallets = jdbc.queryForList(
                "SELECT id, available_balance + locked_balance AS total FROM wallets");
        assertThat(wallets).isNotEmpty();

        for (Map<String, Object> w : wallets) {
            UUID walletId = (UUID) w.get("id");
            BigDecimal total = (BigDecimal) w.get("total");

            BigDecimal ledger = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0) " +
                    "FROM transactions WHERE wallet_id = ?",
                    BigDecimal.class, walletId);

            assertThat(ledger)
                    .as("wallet %s: balance must equal Σcredit − Σdebit", walletId)
                    .isEqualByComparingTo(total);
        }
    }

    @Test
    @DisplayName("every trade posts exactly 4 balanced ledger rows (debits == credits per asset)")
    void everyTradeIsBalancedDoubleEntry() {
        UUID seller = newUser("seller@test.io");
        UUID buyer1 = newUser("buyer1@test.io");
        UUID buyer2 = newUser("buyer2@test.io");

        fund(seller, GOLD, "10");
        fund(buyer1, CASH, "1000");
        fund(buyer2, CASH, "1000");

        place(seller, OrderSide.SELL, GOLD, "100", "10");
        place(buyer1, OrderSide.BUY, GOLD, "100", "4");
        place(buyer2, OrderSide.BUY, GOLD, "100", "6");

        List<UUID> tradeIds = jdbc.queryForList("SELECT id FROM trades", UUID.class);
        assertThat(tradeIds).as("two fills => two trades").hasSize(2);

        for (UUID tradeId : tradeIds) {
            // Join each ledger row back to its wallet's asset so we can balance per asset.
            List<Map<String, Object>> legs = jdbc.queryForList(
                    "SELECT w.asset_id AS asset_id, t.type AS type, t.amount AS amount " +
                    "FROM transactions t JOIN wallets w ON w.id = t.wallet_id " +
                    "WHERE t.reference_type = 'TRADE' AND t.reference_id = ?",
                    tradeId);

            assertThat(legs).as("trade %s posts exactly 4 ledger rows", tradeId).hasSize(4);

            // Within each asset, total debited must equal total credited.
            Map<Object, BigDecimal> signedByAsset = new java.util.HashMap<>();
            for (Map<String, Object> leg : legs) {
                Object asset = leg.get("asset_id");
                BigDecimal amount = (BigDecimal) leg.get("amount");
                BigDecimal signed = "CREDIT".equals(leg.get("type")) ? amount : amount.negate();
                signedByAsset.merge(asset, signed, BigDecimal::add);
            }

            signedByAsset.forEach((asset, net) ->
                    assertThat(net)
                            .as("trade %s asset %s: credits must equal debits", tradeId, asset)
                            .isEqualByComparingTo(BigDecimal.ZERO));
        }
    }
}
