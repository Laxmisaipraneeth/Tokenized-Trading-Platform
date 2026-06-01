# Tokenized Asset Trading Platform — Build Plan

---

## Project Structure

```
com.tokenized.trading/
├── config/                    # Spring config beans (Redis, Security, etc.)
├── domain/
│   ├── model/                 # Wallet, Order, Trade, Transaction (JPA entities)
│   ├── repository/            # JPA repository interfaces
│   └── enums/                 # OrderSide, OrderStatus, TransactionType, AssetSymbol
├── application/
│   ├── service/               # WalletService, OrderService, MatchingEngine, TradeSettlementService
│   └── dto/                   # Request/response DTOs, PlaceOrderRequest, PortfolioSnapshot
├── infrastructure/
│   ├── cache/                 # WalletCacheAdapter, OrderBookRedisAdapter
│   └── lock/                  # DistributedLockService, DistributedLockAspect
├── api/
│   ├── controller/            # WalletController, OrderController, TradeController
│   ├── request/               # Validated request bodies
│   └── response/              # Response wrappers
└── exception/                 # Domain exceptions + GlobalExceptionHandler
```

---

## pom.xml Dependencies

```xml
<!-- Spring Boot Starters -->
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-data-redis
spring-boot-starter-validation
spring-boot-starter-security
spring-boot-starter-actuator

<!-- Database -->
postgresql (runtime)
flyway-core
flyway-database-postgresql

<!-- Redis / Distributed Locks -->
redisson (3.27.x)          <!-- Redlock — do NOT use Lettuce for distributed locks -->

<!-- Testing -->
spring-boot-starter-test
testcontainers (BOM)
  - testcontainers:postgresql
  - testcontainers:redis
junit-jupiter
awaitility                 <!-- async assertions in concurrent tests -->

<!-- Utilities -->
lombok
mapstruct (1.5.x)
jackson-databind

<!-- Observability -->
micrometer-registry-prometheus
```

Version pins: **Java 21, Spring Boot 3.3.x, Redisson 3.27.x, Testcontainers 1.19.x**

---

## Phase 1 — Foundation: Wallet + Transaction Ledger (Days 1–4)

**Goal:** ACID-consistent wallets with a double-entry ledger.

### Flyway Migrations (`src/main/resources/db/migration/`)

| File | What it creates |
|------|----------------|
| `V1__create_users.sql` | users table (id UUID, email, password_hash, role, is_active) |
| `V2__create_assets.sql` | assets table (id, symbol, name, decimal_places, is_tradeable, is_cash) |
| `V3__create_wallets.sql` | wallets table (id, user_id FK, asset_id FK, available_balance, locked_balance, version, timestamps) |
| `V4__create_transactions.sql` | transactions table (id, wallet_id FK, counterparty_wallet_id, type CREDIT/DEBIT, amount, balance_after, reference_id, reference_type, created_at) |
| `V5__create_indexes.sql` | idx on wallets(user_id), idx on transactions(wallet_id, created_at DESC), idx on transactions(reference_id) |

**Critical schema note:** `wallets` has a `version BIGINT` column for optimistic locking fallback, but trade settlement uses **pessimistic locking** (`SELECT FOR UPDATE`) — not optimistic. The version column is a backup, not the primary guard.

### Classes to Build

**Domain:**
- `Wallet` entity — `@Version` field, `debit(amount)` / `credit(amount)` methods that enforce non-negative balance invariant; throw `InsufficientFundsException` inline
- `Transaction` entity — immutable after creation (builder pattern, no setters), stores `balance_after` for ledger traceability
- `TransactionType` enum — `CREDIT`, `DEBIT`

**Repository:**
- `WalletRepository` — `findByUserIdAndAssetId()`, `findByIdWithPessimisticLock()` using `@Lock(PESSIMISTIC_WRITE)`
- `TransactionRepository` — `findByWalletIdOrderByCreatedAtDesc(Pageable)`, `findByReferenceId()`

**Service:**
- `WalletService`:
  - `createWallet(userId, assetId)`
  - `getBalance(userId, assetId)` — cache-aside (Phase 5 wires in Redis; returns DB value for now)
  - `transfer(fromWalletId, toWalletId, amount, referenceId, referenceType)` — `@Transactional`, acquires pessimistic locks in **UUID-sorted order**, debits source, credits destination, writes 2 Transaction records
  - `getTransactionHistory(walletId, Pageable)`

**API:**
- `WalletController`: `POST /api/v1/wallets`, `GET /api/v1/wallets/{userId}/{assetId}/balance`, `GET /api/v1/wallets/{walletId}/transactions`

### Phase 1 Tests (target: 15 tests)

| Test class | What it covers |
|------------|---------------|
| `WalletTest` | debit below zero → exception, debit exact balance → succeeds, version increments |
| `WalletServiceTest` | transfer writes exactly 2 transactions, idempotency (same referenceId → no duplicate) |
| `WalletRepositoryIT` | pessimistic lock blocks second thread, `@Transactional` rollback leaves balances unchanged |
| `WalletServiceIT` | concurrent transfers (10 threads) → no negative balance, sum is conserved |

---

## Phase 2 — Order Book with Redis Sorted Sets (Days 5–8)

**Goal:** O(log N) order placement and retrieval.

### Redis Key Schema

```
orderbook:{symbol}:bids  — Sorted Set
  score = -1 * price     — NEGATIVE so highest bid sorts first via ZRANGE (Redis sorts ascending)
  member = orderId

orderbook:{symbol}:asks  — Sorted Set
  score = +price          — lowest ask sorts first via ZRANGE
  member = orderId

order:{orderId}:details  — Hash
  fields: userId, side, price, quantity, filledQuantity, status, createdAt
```

**Score precision note:** Do not store price as raw `double` — floating-point drift corrupts ordering. Multiply price by `1_000_000` (6 decimal places) and store as `long` cast to `double`. Store the exact `BigDecimal` string in the order hash for all math.

### Flyway Migration

`V6__create_orders.sql` — orders table (id UUID, user_id, asset_id, side, type LIMIT/MARKET, price, quantity, filled_quantity, status OPEN/PARTIALLY_FILLED/FILLED/CANCELLED, created_at, updated_at, expires_at)

Indexes: `(asset_id, side, status, price)`, `(user_id, created_at DESC)`

### Classes to Build

**Domain:** `Order` entity, `OrderSide` enum, `OrderStatus` enum, `OrderType` enum

**Infrastructure:**
- `OrderBookRedisAdapter`:
  - `addOrder(Order)` — ZADD + HSET in a Redis pipeline (both writes together)
  - `getBestBid(symbol)` — `ZRANGE key 0 0` (returns lowest score = most-negative = highest price)
  - `getBestAsk(symbol)` — `ZRANGE key 0 0` (returns lowest score = lowest ask price)
  - `removeOrder(orderId, symbol, side)` — ZREM + DEL hash
  - `updateOrderFill(orderId, filledQty)` — HSET on hash
  - `getTopOfBook(symbol, depth)` — returns top N levels each side

**Service:**
- `OrderService`:
  - `placeOrder(PlaceOrderRequest)` — validate → persist to DB (status=OPEN) → add to Redis → return
  - `cancelOrder(orderId, userId)` — validate ownership → remove from Redis → update DB → release locked balance
  - `getOrderBook(symbol, depth)` — reads from Redis adapter

**API:** `OrderController`: `POST /api/v1/orders`, `DELETE /api/v1/orders/{id}`, `GET /api/v1/orderbook/{symbol}`

### Phase 2 Tests (target: +12, cumulative: 27)

| Test class | What it covers |
|------------|---------------|
| `OrderBookRedisAdapterTest` | add bid → getBestBid returns it, add multiple → highest price returned, score negation is correct |
| `OrderServiceTest` | order placed in both DB and Redis, cancel removes from Redis and releases locked balance |
| `OrderBookIT` | 100 orders at random prices → sorted set order is correct, depth query returns correct top-N |

---

## Phase 3 — Trade Execution Engine (Days 9–13)

**Goal:** Match orders and atomically settle trades.

### Flyway Migration

`V7__create_trades.sql` — trades table (id UUID, buy_order_id FK, sell_order_id FK, buyer_id, seller_id, asset_id, price, quantity, executed_at)

### Core Algorithm — `MatchingEngine`

Run the matching engine **synchronously in Java** (not Lua scripts — Java is testable and debuggable).

```
matchOrder(incomingOrder):
  oppositeSide = incomingOrder.side == BUY ? asks : bids
  
  while incomingOrder.remainingQty > 0:
    bestOpposing = Redis.getBestBid/Ask(symbol)
    if bestOpposing == null: break
    
    if incomingOrder.side == BUY and bestOpposing.price > incomingOrder.price: break
    if incomingOrder.side == SELL and bestOpposing.price < incomingOrder.price: break
    
    fillQty = min(incomingOrder.remainingQty, bestOpposing.remainingQty)
    tradePrice = bestOpposing.price  (resting order sets the price)
    
    tradeSettlementService.settle(incomingOrder, bestOpposing, tradePrice, fillQty)
    
    update both orders in Redis hash and DB
    if bestOpposing is fully filled: ZREM from sorted set
  
  if incomingOrder.remainingQty > 0: add to Redis order book as resting order
```

### `TradeSettlementService` — the most critical class

`@Transactional(isolation = SERIALIZABLE)` (or use explicit `SELECT FOR UPDATE` — see note below)

Steps inside one DB transaction:
1. Acquire pessimistic locks on all 4 wallets in **UUID-sorted order**
2. Debit buyer's CASH wallet by `qty × price`
3. Credit buyer's asset wallet by `qty`
4. Debit seller's asset wallet by `qty`
5. Credit seller's CASH wallet by `qty × price`
6. Write 1 Trade record
7. Write 4 Transaction records (one per wallet movement)
8. Update `filled_quantity` and `status` on both Order records
9. Commit

**Post-commit (via `@TransactionalEventListener(AFTER_COMMIT)`):**
- Invalidate Redis wallet cache for all 4 wallets
- Update Redis order book (remove filled orders)

### Classes to Build

- `MatchingEngine` — the matching loop above
- `TradeSettlementService` — atomic 4-wallet settlement
- `TradeExecutionService` — orchestrator: validates order → calls MatchingEngine
- `Trade` entity
- `TradeController`: `POST /api/v1/trades/execute`, `GET /api/v1/trades/{userId}`

### Phase 3 Tests (target: +20, cumulative: 47)

| Test class | What it covers |
|------------|---------------|
| `MatchingEngineTest` | full fill, partial fill, multi-level sweep, no fill (prices don't cross), market order |
| `TradeSettlementServiceTest` | 4 wallet balance changes are correct, trade + 4 transaction records written, rollback on failure |
| `TradeExecutionIT` | end-to-end: create wallets → place buy → place matching sell → verify balances + trade record + Redis updated |
| `ConcurrentTradeIT` | 2 threads submit crossing orders simultaneously → trade executes exactly once, no double settlement |

---

## Phase 4 — Redis Redlock (Days 14–16)

**Goal:** Prevent double-spend across concurrent trade executions.

### Why Redlock on Top of DB Locks

Pessimistic DB locks protect wallet rows. Redlock protects the **order matching decision** itself — preventing two threads from both deciding to match the same resting order before either has updated its status. Redlock wraps the entire `executeOrder()` call.

### Classes to Build

`RedissonConfig` — single-node config for dev (3-node for production Redlock quorum):
```java
@Bean
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer().setAddress("redis://localhost:6379");
    return Redisson.create(config);
}
```

`DistributedLockService`:
- `acquireLock(key, waitSeconds, leaseSeconds)` — `RLock.tryLock()`
- `releaseLock(key)` — `RLock.unlock()` with `isHeldByCurrentThread()` guard
- Lock keys: `lock:trade:execute:{userId}`, `lock:wallet:{walletId}`

`@DistributedLock` annotation + `DistributedLockAspect` (AOP):
- Annotate `TradeExecutionService.executeOrder()` with `key = "lock:trade:execute:{#request.userId}"`
- `LockAcquisitionException` → 409 Conflict (order stays in book, client retries)

Multi-wallet lock pattern in `TradeSettlementService`:
```java
List<String> lockKeys = Stream.of(buyerCashWalletId, buyerAssetWalletId, sellerAssetWalletId, sellerCashWalletId)
    .map(UUID::toString)
    .sorted()   // ALWAYS sort to prevent deadlock
    .map(id -> "lock:wallet:" + id)
    .toList();
// Acquire all 4 in sorted order, then proceed with settlement
```

### Phase 4 Tests (target: +8, cumulative: 55)

| Test class | What it covers |
|------------|---------------|
| `DistributedLockServiceTest` | acquire succeeds, second acquire on same key blocks, auto-expire allows reacquire |
| `DoubleSpendPreventionIT` | 2 threads call `executeOrder` for same user simultaneously → only 1 succeeds, 1 gets 409, balance is correct |

---

## Phase 5 — Redis Caching Layer (Days 17–19)

**Goal:** 60% PostgreSQL read reduction on wallet queries; 45% portfolio latency reduction.

### Wallet Balance Cache

Key: `wallet:balance:{userId}:{assetId}` — TTL 30 seconds  
Value: JSON `{"available": "123.45", "locked": "50.00"}`

`WalletCacheAdapter`:
- `getCache(userId, assetId)` → `Optional<WalletBalance>`
- `setCache(userId, assetId, balance)` — SETEX 30s
- `invalidate(userId, assetId)` — DEL
- `invalidateAllForUser(userId)` — maintain a Set key `wallet:keys:{userId}` to track active keys (avoids SCAN in production)

Cache-aside in `WalletService.getBalance()`:
1. Check Redis cache → return if hit
2. Miss → query PostgreSQL → store in cache → return

Cache invalidation in `WalletService.transfer()`:
- Use `@TransactionalEventListener(phase = AFTER_COMMIT)` to invalidate — ensures Redis is only updated after DB commit, never on rollback

### Portfolio Cache

Key: `portfolio:{userId}` — TTL 60 seconds  
Value: JSON list of all wallet balances for user

`PortfolioService.getPortfolio(userId)`:
1. Check `portfolio:{userId}` in Redis
2. Miss → query all wallets from DB → cache result → return
3. Invalidate on any trade settlement for that userId

### Metrics (proves the resume claims)

Add Micrometer counters in `WalletCacheAdapter`:
```java
cacheHitCounter = meterRegistry.counter("wallet.cache.hit");
cacheMissCounter = meterRegistry.counter("wallet.cache.miss");
```

Expose at `/actuator/metrics/wallet.cache.hit` and `wallet.cache.miss`. Hit rate = hits / (hits + misses). Target: ≥60% after warmup.

### Phase 5 Tests (target: +10, cumulative: 65)

| Test class | What it covers |
|------------|---------------|
| `WalletCacheAdapterTest` | set → get returns cached, TTL expires → returns empty, invalidate removes key |
| `PortfolioServiceTest` | cache hit → DB not called (mock verify), invalidation on trade |
| `CacheEffectivenessIT` | 100 reads after first cache population → DB called exactly once |
| `CacheInvalidationIT` | execute trade → portfolio cache invalidated → next read reflects new balance |

---

## Phase 6 — Testing Hardening (Days 20–23)

**Goal:** Reach 80+ tests, prove financial correctness.

### Additional Edge Case Tests

**Order matching edge cases:**
- Sweep through 3 price levels in one order
- Market order with empty order book → reject with 422
- Quantity of 0 → reject with 400
- Price with too many decimal places → reject with 400

**Concurrent correctness:**
- 20 threads simultaneously credit the same wallet → final balance = sum of all credits (no lost updates)
- 10 threads place overlapping orders → no order matched twice

**Financial conservation tests (critical for resume credibility):**
- After N random trades: `sum(all wallet balances per asset)` = initial total supply — tests the "zero money loss" claim
- Every trade has exactly 4 corresponding Transaction records
- No wallet ever has `available_balance < 0` or `locked_balance < 0`

**Ledger reconciliation:**
- `sum(transactions.amount WHERE type=DEBIT) = sum(transactions.amount WHERE type=CREDIT)` always balances

### API Hardening

`GlobalExceptionHandler` (@ControllerAdvice) — map exceptions to HTTP codes:
```
InsufficientFundsException     → 422 Unprocessable Entity
OrderNotFoundException         → 404 Not Found
LockAcquisitionException       → 409 Conflict (retry)
ConstraintViolationException   → 400 Bad Request
DuplicateOrderException        → 409 Conflict
```

Add Bean Validation to all request DTOs: `@NotNull`, `@Positive`, `@DecimalMin("0.00000001")`

Add `Idempotency-Key` header support to order placement to prevent duplicate orders on client retry.

### Final Test Count Target

| Phase | New Tests | Cumulative |
|-------|-----------|------------|
| Phase 1 | 15 | 15 |
| Phase 2 | 12 | 27 |
| Phase 3 | 20 | 47 |
| Phase 4 | 8 | 55 |
| Phase 5 | 10 | 65 |
| Phase 6 | 15+ | **80+** |

---

## application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tokenized_trading
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway owns the schema — never let Hibernate touch it
    properties:
      hibernate:
        jdbc.batch_size: 50
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 20

trading:
  cache:
    wallet-ttl-seconds: 30
    portfolio-ttl-seconds: 60
  lock:
    default-wait-seconds: 3
    default-lease-seconds: 10
  order-book:
    default-depth: 20
```

---

## The 4 Hardest Decisions (Already Made)

**1. Pessimistic locking, not optimistic**
Under high concurrency, optimistic locking (`@Version`) causes cascading `ObjectOptimisticLockingFailureException` retries that destroy throughput. Use `SELECT FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)` for all wallet settlement. Always acquire in UUID-sorted order to prevent deadlock.

**2. Redis score = `-price` for buy side**
Redis Sorted Sets sort ascending. Highest bid must come first. Storing `-price` as the score means the highest bid (e.g., $100) has score `-100`, which is less than the next bid's score of `-99`, so it sorts first. This is the only correct approach.

**3. Matching engine in Java, not Lua**
Redis Lua scripts look elegant but are a nightmare to test, debug, and extend. Run the matching loop in Java; use Redis only for sorted set queries. A full multi-fill match is still well under 20ms.

**4. `@TransactionalEventListener(AFTER_COMMIT)` for cache invalidation**
Never invalidate the Redis cache inside `@Transactional` — if the transaction rolls back, you've already deleted the cache, and the next read re-caches the (now stale, pre-rollback) DB value. Fire invalidation only after the transaction commits.

---

## Success Metrics to Demonstrate

| Claim | How to Measure |
|-------|---------------|
| <5ms p99 trade execution | JMeter/Gatling test with pre-seeded order book, measure p99 |
| O(log N) order book | Redis ZADD/ZRANGE — measure with 10k orders, show time stays flat |
| 60% DB read reduction | `/actuator/metrics/wallet.cache.hit` — hit rate ≥ 60% after warmup |
| 45% portfolio latency reduction | Measure `getPortfolio` response time cold vs. warm cache |
| 80+ tests | `mvn test` output — screenshot for portfolio |
| Zero double-spend | `DoubleSpendPreventionIT` passing with concurrent load |
