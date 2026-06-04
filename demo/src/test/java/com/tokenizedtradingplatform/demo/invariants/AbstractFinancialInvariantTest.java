package com.tokenizedtradingplatform.demo.invariants;

import com.tokenizedtradingplatform.demo.api.request.DepositRequest;
import com.tokenizedtradingplatform.demo.api.request.PlaceOrderRequest;
import com.tokenizedtradingplatform.demo.application.service.OrderService;
import com.tokenizedtradingplatform.demo.application.service.WalletService;
import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import com.tokenizedtradingplatform.demo.domain.model.User;
import com.tokenizedtradingplatform.demo.domain.model.Wallet;
import com.tokenizedtradingplatform.demo.domain.repository.UserRepository;
import com.tokenizedtradingplatform.demo.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared harness for the three financial-correctness invariants:
 *   1. Wealth conservation   — trades only move value between users, never create/destroy it.
 *   2. No double-spend        — concurrent matching against one resting order never over-fills it.
 *   3. Ledger reconciliation  — the immutable transaction ledger always reconciles to wallet state.
 *
 * Uses real Postgres + Redis via Testcontainers so the pessimistic locks, Flyway schema,
 * CHECK constraints and Redis order book all behave exactly as in production.
 *
 * NOT annotated @Transactional on purpose: each placeOrder must commit so the AFTER_COMMIT
 * listeners (order-book ZADD, cache invalidation) fire and the next order sees a real book.
 */
@SpringBootTest
abstract class AbstractFinancialInvariantTest {

    // Seeded by Flyway V2: CASH=id 1, GOLD=id 2, SLVR=id 3, REIT=id 4.
    protected static final int CASH = 1;
    protected static final int GOLD = 2;

    // Singleton containers: started once, shared by every test class, never torn down between
    // classes. This is deliberate — all the invariant test classes share one @SpringBootTest
    // context (Spring caches it), so the container ports must stay fixed for the whole suite.
    // Ryuk reaps both containers at JVM shutdown.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired protected OrderService orderService;
    @Autowired protected WalletService walletService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected WalletRepository walletRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected RedissonClient redisson;

    @BeforeEach
    void resetState() {
        // Wipe all mutable rows (keep the Flyway-seeded assets) and clear the Redis book + caches.
        jdbc.execute("TRUNCATE transactions, trades, orders, wallets, users RESTART IDENTITY CASCADE");
        redisson.getKeys().flushall();
    }

    // ── Test fixtures ───────────────────────────────────────────────────────────

    protected UUID newUser(String email) {
        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password123"))
                .fullName(email)
                .build());
        return user.getId();
    }

    protected void fund(UUID userId, int assetId, String amount) {
        walletService.getOrCreateWallet(userId, assetId);
        DepositRequest dep = new DepositRequest();
        dep.setAmount(bd(amount));
        walletService.deposit(userId, assetId, dep);
    }

    protected void place(UUID userId, OrderSide side, int assetId, String price, String qty) {
        orderService.placeOrder(userId, new PlaceOrderRequest(assetId, side, bd(price), bd(qty)));
    }

    // ── Read helpers (query the DB directly, bypassing the cache) ────────────────

    /** Total balance (available + locked) for one user's wallet of an asset, or ZERO if none. */
    protected BigDecimal balance(UUID userId, int assetId) {
        return walletRepository.findByUserIdAndAssetId(userId, assetId)
                .map(Wallet::getTotalBalance)
                .orElse(BigDecimal.ZERO);
    }

    /** Sum of (available + locked) across every wallet holding the asset — the system-wide supply. */
    protected BigDecimal totalSupply(int assetId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_balance + locked_balance), 0) FROM wallets WHERE asset_id = ?",
                BigDecimal.class, assetId);
    }

    protected long negativeBalanceCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE available_balance < 0 OR locked_balance < 0",
                Long.class);
    }

    protected static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
