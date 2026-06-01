# Build Progress

## Status: Phase 5 Complete ✅

---

## Phase 1 — Foundation (DONE)

All files created, app boots on port 8080, JWT login works.

### What was built
- `application.yml`, `DemoApplication.java`
- Flyway V1–V4 (users, assets, wallets, transactions)
- Assets seeded: CASH (id=1), GOLD (id=2), SLVR (id=3), REIT (id=4)
- Auth: `JwtUtil`, `JwtAuthenticationFilter`, `SecurityConfig`, `AuthService`, `AuthController`
- Wallet stack: `Wallet` entity (with `credit/debit/reserve/releaseReservation/settleDebit`),
  `WalletRepository` (with pessimistic lock variants), `WalletService`, `WalletController`
- Exception layer: `GlobalExceptionHandler` + 3 custom exceptions
- Asset listing: `AssetController`

---

## Phase 2 — Order Book (DONE)

Live-tested: BUY order placed → order book shows bid → wallet locked correctly.

### What was built
- Flyway V5 — `orders` table (VARCHAR + CHECK for enums)
- Enums: `OrderSide`, `OrderStatus`, `OrderType`
- `Order` entity with `fill()` and `cancel()` business methods
- `OrderRepository` — with `findByIdWithLock`, JOIN FETCH, status filter
- `OrderBookRedisAdapter` — Redis Sorted Sets
  - BUY score = `-price × 1_000_000`, SELL score = `+price × 1_000_000`
  - Keys: `orderbook:{symbol}:bids`, `orderbook:{symbol}:asks`
- DTOs: `PlaceOrderRequest`, `OrderResponse`, `OrderBookResponse`
- `OrderService` — place (reserve→persist→add to Redis), cancel (Redis→release→DB)
- `OrderController`: `POST /orders`, `DELETE /orders/{id}`, `GET /orderbook/{symbol}`

---

## Phase 3 — Trade Execution Engine (DONE)

Live-tested with two users + leftover order from prior session. Multi-counterparty
fills + partial fills + price-improvement refunds all confirmed working. Financial
conservation verified manually (∑ cash deltas = 0, ∑ GOLD preserved).

### What was built
- Flyway V6 — `trades` table with `total_amount = price * quantity` CHECK constraint
- `Trade` entity (immutable, no setters) referencing buyOrder/sellOrder/buyer/seller/asset
- `TradeRepository` — `findByUserId` (paged buyer OR seller), `findByAssetId`, `findByOrderId`
- `TradeResponse` DTO with static `from(Trade)` factory
- `TradeSettlementService` — the atomic 4-wallet settlement:
  1. Ensure all 4 wallets exist (creates buyer.asset / seller.cash if missing)
  2. Lock all 4 in UUID-sorted order via `findByIdWithLock` (deadlock-free)
  3. `buyer.cash.settleDebit(qty × tradePrice)`, refund extra reserved if buyer's
     limit price was higher than trade price → `releaseReservation(refund)`
  4. `seller.asset.settleDebit(qty)`, `buyer.asset.credit(qty)`, `seller.cash.credit(totalCost)`
  5. Save Trade + 4 Transaction ledger rows (reference_type="TRADE")
  6. Call `buyOrder.fill(qty)` and `sellOrder.fill(qty)` — auto-transitions status
- `MatchingEngine` — price-time priority loop
  - Peek best opposing via `orderBook.getBestAsk/Bid`
  - `findByIdWithLock` on opposing order; skip+cleanup if stale (cancelled/filled)
  - Price-cross check, fill = min(remaining qtys), tradePrice = resting order's price
  - Delegate to settlementService; ZREM opposing if fully filled
- `TradeExecutionService` — orchestrator called from OrderService:
  - `execute(incoming)` → matchingEngine.match() → rest remainder in Redis
  - `getUserTrades`, `getAssetTrades`, `getOrderTrades` read-only methods
- `TradeController`: `GET /trades`, `GET /trades/order/{id}`, `GET /trades/asset/{id}`
- Modified `OrderService.placeOrder()` — now calls `tradeExecutionService.execute(order)`
  instead of `orderBook.addOrder()` directly

### Known limitation (to address in Phase 5)
Redis ops happen inside the DB transaction, so a rollback after a Redis write leaves
the order book inconsistent with the DB. Will switch to `@TransactionalEventListener(AFTER_COMMIT)`
in Phase 5 alongside cache invalidation.

---

## Phase 4 — Redlock (DONE)

Live-tested: contention case proven — squatted on the lock key from `redis-cli` with
a Redisson-formatted HASH, then a placeOrder call returned `409 Conflict` after exactly
the 3s `waitSeconds` timeout. Lock acquire/release also visible in DEBUG logs.

### What was built
- Added `spring-boot-starter-aop` to pom.xml
- `LockAcquisitionException` → 409 Conflict in `GlobalExceptionHandler`
- `DistributedLockService` — wraps Redisson `RLock.tryLock(wait, lease, SECONDS)`
- `@DistributedLock(key, waitSeconds, leaseSeconds)` annotation
- `DistributedLockAspect`:
  - `@Order(Ordered.HIGHEST_PRECEDENCE + 100)` — runs OUTSIDE `@Transactional`,
    so the lock is held until after commit
  - SpEL template parsing (`#{ ... }`) with method parameter names as variables
  - `try { proceed } finally { release }` for guaranteed release
- Applied to `OrderService.placeOrder()` and `OrderService.cancelOrder()` with
  key `lock:trade:execute:#{#userId}` — serializes per-user concurrent placements
- `application.yml` logging override to surface lock acquire/release at DEBUG

### Key design decisions
- **Per-user lock granularity, not per-wallet.** Wallets are protected at the DB level
  via UUID-sorted pessimistic locks in `TradeSettlementService`. The Redlock catches
  the case where one user submits two orders concurrently — they get serialized.
- **Lock OUTSIDE the transaction, not inside.** `@Order(HIGHEST_PRECEDENCE + 100)`
  is lower than Spring's `@Transactional` order (`LOWEST_PRECEDENCE`), so the
  AOP advice wraps the transaction proxy. Acquire → tx begin → work → tx commit →
  release. If we released before commit, another thread could read pre-commit state.
- **Lease (10s)** — auto-expires so a crashed holder doesn't deadlock the system.

### Known limitation (Phase 5 fix)
Redis ops still happen inside the DB transaction → switching to
`@TransactionalEventListener(AFTER_COMMIT)` in Phase 5.

---

## Phase 5 — Redis Caching + Event-Driven Invalidation (DONE)

Live-verified end-to-end:
- Wallet cache: **5/6 = 83% hit rate** on serial reads
- Portfolio cache: **4/5 = 80% hit rate**
- Invalidation: deposit fires WalletBalanceChangedEvent → AFTER_COMMIT listener
  invalidates both wallet + portfolio caches → next read is a miss → cache repopulates
- Order book: OrderRestedEvent → AFTER_COMMIT listener fires Redis ZADD → resting
  bid visible in /orderbook (closes the rollback-leaves-phantom-order gap)
- Metrics live at `/actuator/metrics/{wallet,portfolio}.cache.{hit,miss}`

### What was built
- `application/event/WalletBalanceChangedEvent` (record) — emitted whenever a wallet
  changes (deposit, withdraw, reserve, releaseReservation, all 4 wallets in settlement)
- `application/event/OrderRestedEvent` (record) — emitted when an order has remaining
  qty after matching and needs to enter the book
- `application/event/WalletEventListener` — `@TransactionalEventListener(AFTER_COMMIT)`
  invalidates both wallet AND portfolio cache for the user
- `application/event/OrderBookEventListener` — `@TransactionalEventListener(AFTER_COMMIT)`
  applies the ZADD to the Redis sorted set
- `infrastructure/cache/WalletCacheAdapter` — string-bucket JSON via Spring's autoconfigured
  ObjectMapper, Micrometer hit/miss counters, TTL 30s
- `infrastructure/cache/PortfolioCacheAdapter` — same pattern, TTL 60s
- `application/service/PortfolioService` — cache-aside; loads all wallets via the existing
  `findByUserId` JOIN FETCH, returns `PortfolioResponse`
- `api/response/PortfolioResponse` (record) — userId, list of WalletResponse, snapshotAt
- `WalletController.GET /api/v1/wallets/portfolio` — cached endpoint

### Modified
- `WalletService.getWallet()` — cache-aside (miss → DB → put → return)
- `WalletService.deposit()` / `withdraw()` — publish `WalletBalanceChangedEvent`
- `OrderService.placeOrder()` / `cancelOrder()` — publish event after wallet write
- `TradeSettlementService.settle()` — publishes 4 events (buyer/seller × cash/asset)
- `TradeExecutionService.execute()` — switched direct `orderBook.addOrder()` to
  publishing `OrderRestedEvent`; dropped the `OrderBookRedisAdapter` field
- `WalletResponse` — added `@NoArgsConstructor` for Jackson deserialization
- `application.yml` — added DEBUG logging for `infrastructure.cache` and `application.event`

### Key design decisions
- **String buckets, not Redisson's polymorphic codec.** Redisson's `JsonJacksonCodec` forces
  `@class` type tags globally, which broke when caching `PortfolioResponse` (a record
  containing `List<WalletResponse>`). Storing plain JSON strings and using Spring's
  autoconfigured ObjectMapper (which already has JSR-310 / `Instant` support) is simpler
  and more debuggable — you can `redis-cli GET <key>` and read the value.
- **Invalidate, don't update.** The cache listener `delete`s the key rather than
  re-computing the new value. Next read repopulates from DB. This is safer under
  concurrent updates (no "last writer wins" race).
- **AFTER_COMMIT for everything Redis-mutating.** A transaction rollback never leaks
  a stale invalidation, a phantom resting order, or a half-applied event.
- **Mid-matching ZREM stays direct.** The matching loop *needs* Redis to reflect the
  current book mid-iteration, so `MatchingEngine.removeOrder` calls stay synchronous.
  The matcher's existing stale-entry skip-and-cleanup handles any rollback drift.

---

## Phase 6 — Testing (PENDING)

Target: 80+ unit + integration tests.
- Unit: Wallet balance math, order validation, matching logic edge cases, settlement math
- Integration (Testcontainers): full trade lifecycle, concurrent trades, double-spend
  prevention, cache invalidation, financial conservation
- Tools: JUnit 5, Mockito, Testcontainers (Postgres + Redis), Awaitility

---

## Environment
- Java: 25.0.2
- Spring Boot: 3.5.14
- PostgreSQL: 15.17 (`tokenized_trading` @ localhost:5432, postgres/postgres)
- Redis: localhost:6379
- Run: `cd demo && mvn spring-boot:run`

## Endpoints summary
```
POST   /api/v1/auth/register          # public
POST   /api/v1/auth/login             # public
GET    /api/v1/assets                 # all tradeable
GET    /api/v1/assets/{id}
GET    /api/v1/wallets                # current user portfolio
GET    /api/v1/wallets/{assetId}
POST   /api/v1/wallets/{assetId}      # create wallet
POST   /api/v1/wallets/{assetId}/deposit
POST   /api/v1/wallets/{assetId}/withdraw
GET    /api/v1/wallets/{assetId}/transactions
POST   /api/v1/orders                 # place
DELETE /api/v1/orders/{orderId}       # cancel
GET    /api/v1/orders                 # paged history
GET    /api/v1/orders/open
GET    /api/v1/orderbook/{symbol}
GET    /api/v1/trades                 # my trade history
GET    /api/v1/trades/order/{orderId} # fills for one of my orders
GET    /api/v1/trades/asset/{assetId} # public market history
```
