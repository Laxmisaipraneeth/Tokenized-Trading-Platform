# Tokenized Asset Trading Platform — Requirements Document

**Tech Stack:** Java · Spring Boot · PostgreSQL · Redis  
**Timeline:** April 2026 – Present  
**Status:** In Development

---

## 1. What We Are Building

A backend trading engine where users can buy and sell tokenized assets — digital tokens representing real-world value (commodities, equities, currencies). The system is modeled after how a real stock exchange works:

1. **Users hold wallets** — one per asset, plus one cash wallet for the currency used to buy tokens
2. **Users place orders** — "I want to buy 10 GOLD tokens at $50 each"
3. **A matching engine pairs buyers and sellers** — when a buy price ≥ sell price, a trade fires
4. **Wallets settle atomically** — funds move in all four directions or not at all
5. **Every trade is permanently recorded** in an immutable transaction ledger

**The core engineering problems:**
- **Double-spend prevention**: Two concurrent buy orders on the same wallet must not both pass a balance check before either deducts. Solved with Redis Redlock distributed locking.
- **Sub-5ms trade execution**: Order book lookups and matching must be in-memory. Solved with Redis Sorted Sets for O(log N) price-priority access.
- **ACID financial correctness**: Wallet debits, credits, and ledger writes must be atomic. Solved with PostgreSQL transactions.
- **60% PostgreSQL read reduction**: Active wallet balances are cached in Redis. Reads hit cache first; PostgreSQL only on cache miss or write-through invalidation.

---

## 2. Architecture

```
┌─────────────────────────────────────────────┐
│              REST API Layer                 │
│         (Spring Boot Controllers)           │
└──────────────┬──────────────────────────────┘
               │
┌──────────────▼──────────────────────────────┐
│             Core Service Modules            │
├─────────────────────────────────────────────┤
│  WalletService       │  OrderService         │
│  TradeExecutionSvc   │  TransactionLedger    │
│  MatchingEngine      │  AssetService         │
└──────┬───────────────────────────┬──────────┘
       │                           │
┌──────▼──────┐           ┌────────▼────────┐
│  PostgreSQL  │           │     Redis        │
│  (source of  │           │  • Order book    │
│   truth)     │           │  • Wallet cache  │
│              │           │  • Redlock       │
└─────────────-┘           └─────────────────┘
```

**Data flow for a trade:**
1. User POSTs an order → validated → wallet balance reserved (locked)
2. Order inserted into Redis Sorted Set order book
3. Matching engine checks for compatible opposite-side orders
4. On match: Redlock acquired on both wallets → PostgreSQL transaction executes → wallets updated → ledger written → Redis cache invalidated → locks released

---

## 3. Functional Requirements

### 3.1 User & Account Management

**Registration & Authentication**
- [ ] Register with email + password; hash password with bcrypt (12+ rounds)
- [ ] Login returns a JWT (1-hour expiry, refresh token support)
- [ ] Logout invalidates token (stored in Redis blacklist with TTL)
- [ ] Role-based access: `TRADER` (default) and `ADMIN`

**What is NOT in scope:**
- Email verification, MFA, KYC/AML, IP whitelisting — these are operational concerns outside this project's core engineering goals

---

### 3.2 Wallet Service

**Core Concept**
Every user has one wallet per asset. There is also a special `CASH` asset representing the fiat currency used to buy tokens. A user with `CASH` balance of $500 and `GOLD` balance of 10 means they hold $500 in purchasing power and 10 gold tokens.

**Wallet Initialization**
- [ ] Auto-create wallet for a user+asset pair on first deposit or order
- [ ] Separate `available_balance` and `locked_balance` per wallet
  - `available_balance`: funds free to use
  - `locked_balance`: funds reserved for an open order
  - `total_balance = available_balance + locked_balance`
- [ ] Track `created_at` and `updated_at` timestamps

**Balance Operations**
- [ ] **Deposit**: Increase `available_balance` (admin-triggered or mock endpoint)
- [ ] **Withdraw**: Decrease `available_balance` if sufficient; reject otherwise
- [ ] **Reserve**: Move amount from `available_balance` → `locked_balance` when order is placed
- [ ] **Release**: Move amount from `locked_balance` → `available_balance` when order is cancelled
- [ ] **Settle**: Deduct from `locked_balance` (buyer's cash, seller's tokens) and credit `available_balance` (buyer's tokens, seller's cash) atomically during trade execution

**Hot-Wallet Caching (Redis)**
- [ ] On wallet read: check `wallet:{userId}:{assetId}` in Redis first
- [ ] On cache miss: read from PostgreSQL, populate cache with 5-minute TTL
- [ ] On balance write: update PostgreSQL first (source of truth), then invalidate Redis key
- [ ] **Target**: 60% reduction in PostgreSQL wallet read queries; 45% reduction in portfolio query latency
- [ ] Portfolio endpoint aggregates all wallets for a user — served entirely from Redis cache when warm

---

### 3.3 Asset Management

- [ ] Define tradeable assets: `symbol` (e.g. `GOLD`, `BTC`), `name`, `decimal_precision`, `is_tradeable` flag
- [ ] `CASH` is a special system asset representing purchasing currency — always exists, not tradeable as an order side
- [ ] Admin endpoint to create assets, toggle trading status (circuit breaker)
- [ ] When `is_tradeable = false`, reject all new orders for that asset (existing open orders remain)

---

### 3.4 Order Book (Redis Sorted Sets)

**Data Structure**

Each asset has two sorted sets in Redis:

```
orderbook:buy:{assetId}   → Buy-side (bids)
orderbook:sell:{assetId}  → Sell-side (asks)

Each member: "{orderId}"
Score encoding (critical for correct price priority):
  Buy side:  score = -price   (ZRANGEBYSCORE gives highest bid first — lowest negative score)
  Sell side: score = +price   (ZRANGEBYSCORE gives lowest ask first)
```

Why negative scores on the buy side: Redis Sorted Sets sort ascending by score. For bids, we want the highest price first (best bid). Storing `-price` as the score means the highest bid has the most negative score, which sorts first in `ZRANGE`/`ZRANGEBYSCORE`.

**Order detail hash:**
```
orderbook:order:{orderId} → Hash
  user_id, side, price, quantity, remaining_quantity, created_at
```

**Operations**
- [ ] `ZADD orderbook:buy:{assetId} -price orderId` → Insert buy order (O(log N))
- [ ] `ZADD orderbook:sell:{assetId} price orderId` → Insert sell order (O(log N))
- [ ] `ZRANGE orderbook:buy:{assetId} 0 0` → Peek best bid (O(log N))
- [ ] `ZRANGE orderbook:sell:{assetId} 0 0` → Peek best ask (O(log N))
- [ ] `ZREM orderbook:{side}:{assetId} orderId` → Remove filled/cancelled order (O(log N))

---

### 3.5 Order Submission & Validation

**Supported Order Types**
- [ ] **Limit Order**: Place at specific price; waits in order book until matched or cancelled
- [ ] **Market Order**: Execute immediately at best available price; reject if no liquidity

*(Stop orders and iceberg orders are not in scope for this project)*

**Validation on Order Submission**
- [ ] Price > 0, quantity > 0 (or omit price for market orders)
- [ ] Asset exists and `is_tradeable = true`
- [ ] User is authenticated
- [ ] Sufficient available balance:
  - BUY order: `available_balance(CASH) ≥ price × quantity`
  - SELL order: `available_balance(asset) ≥ quantity`
- [ ] Reserve the balance immediately on order acceptance (move to `locked_balance`)

**Response**
- [ ] Return: `orderId`, `status` (PENDING/PARTIAL/FILLED), `timestamp`
- [ ] Persist order to PostgreSQL `orders` table
- [ ] Add order to Redis order book

---

### 3.6 Trade Matching Engine

**Algorithm: Price-Time Priority**
- Match best price first (highest bid vs. lowest ask)
- Among equal prices, match earliest order first (FIFO)
- Execute when: `best_bid_price ≥ best_ask_price`

**Matching Loop (runs after every new order insertion)**
```
while (best_bid.price >= best_ask.price):
    trade_price = best_ask.price  (or mid-price — define and stick to one)
    trade_qty   = min(bid.remaining_qty, ask.remaining_qty)
    executeTrade(bid, ask, trade_price, trade_qty)
    update remaining quantities in Redis hash
    if order fully filled: ZREM from sorted set, mark FILLED in PostgreSQL
```

**Partial Fills**
- [ ] Order status transitions: `PENDING` → `PARTIAL` → `FILLED`
- [ ] Update `filled_quantity` in PostgreSQL orders table after each partial execution
- [ ] Update `remaining_quantity` in Redis order hash

---

### 3.7 Trade Execution & Wallet Settlement

**Execution Steps (inside a single PostgreSQL transaction)**
1. Deduct `locked_balance` of buyer's CASH wallet by `trade_qty × trade_price`
2. Deduct `locked_balance` of seller's asset wallet by `trade_qty`
3. Credit `available_balance` of buyer's asset wallet by `trade_qty`
4. Credit `available_balance` of seller's CASH wallet by `trade_qty × trade_price`
5. Write record to `transactions` table with status `COMPLETED`
6. Update `filled_quantity` and `status` in `orders` table for both orders
7. Commit transaction

**On any failure:** PostgreSQL rolls back all 7 steps atomically — no partial settlement

**Post-commit:**
- [ ] Invalidate Redis wallet cache for all four affected wallets
- [ ] Release Redlock (see section 3.8)
- [ ] Update order book in Redis (remove filled orders)

---

### 3.8 Distributed Locking (Redis Redlock)

**Why it's needed**
Two concurrent trades on the same wallet could both read a sufficient balance, both proceed, and together exceed the actual balance — a double-spend. Redlock ensures only one trade can touch a wallet at a time.

**Lock Pattern**
```
Lock keys:
  lock:wallet:{userId}:{assetId}
  
Acquisition order (always alphabetical to prevent deadlock):
  sort [buyer_cash_key, buyer_asset_key, seller_cash_key, seller_asset_key]
  acquire all four locks before proceeding
```

**Configuration**
- [ ] Lock TTL: 30 seconds (prevents stale locks if process crashes mid-trade)
- [ ] Acquisition timeout: 5 seconds with exponential backoff (50ms → 100ms → 200ms → fail)
- [ ] On lock acquisition failure: return HTTP 409 (Conflict) — order remains in book, caller retries
- [ ] Use Redisson library for Redlock implementation in Java

**Single Redis node for dev/test; for production Redlock correctness, 3+ independent nodes are required (quorum-based)**

---

### 3.9 Transaction Ledger & Audit Trail

**Purpose:** Immutable, append-only record of every completed trade for financial traceability

**Ledger entry contains:**
- [ ] Buyer ID, Seller ID
- [ ] Asset ID, quantity traded, price per unit, total amount
- [ ] Reference to both matched order IDs (`buyer_order_id`, `seller_order_id`)
- [ ] `status`: COMPLETED (or FAILED if settlement rolled back)
- [ ] `created_at` timestamp

**Queries**
- [ ] Get all transactions for a user (paginated, filterable by date range and asset)
- [ ] Get a specific transaction by ID
- [ ] Get all trades for an asset (market history)

**Consistency guarantee:** Ledger entry is written inside the same PostgreSQL transaction as wallet settlement — if the DB transaction commits, the ledger entry exists; if it rolls back, no ledger entry is created

---

## 4. Non-Functional Requirements

### 4.1 Performance Targets

| Metric | Target | How Achieved |
|--------|--------|--------------|
| Trade execution (match → ledger) | <5ms p99 | Redis order book + minimal DB ops |
| Order book lookup (best bid/ask) | O(log N) | Redis Sorted Set ZRANGE |
| Wallet balance read | <1ms (cache hit) | Redis hot-wallet cache |
| PostgreSQL read load (wallets) | -60% vs. no-cache | Redis cache with 5-min TTL |
| Portfolio query latency | -45% vs. no-cache | Full portfolio served from Redis |
| Throughput | 1000+ trades/sec | In-memory matching, async ledger writes |

### 4.2 Data Integrity
- [ ] All wallet settlements use PostgreSQL transactions (ACID)
- [ ] No partial trades — all-or-nothing via transaction rollback
- [ ] Redlock prevents concurrent wallet modification
- [ ] Total user wealth is conserved across every trade (sum of all wallets unchanged)

### 4.3 Security
- [ ] JWT authentication on all protected endpoints
- [ ] Bcrypt password hashing (12+ rounds)
- [ ] Parameterized queries only — no string-concatenated SQL
- [ ] Users can only access their own wallets, orders, and transactions
- [ ] Rate limiting: 100 requests/minute per user (Redis counter with 1-minute TTL)

---

## 5. Database Schema (PostgreSQL)

### users
```sql
CREATE TABLE users (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email       VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  full_name   VARCHAR(255),
  role        VARCHAR(20) NOT NULL DEFAULT 'TRADER',
  is_active   BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### assets
```sql
CREATE TABLE assets (
  id            SERIAL PRIMARY KEY,
  symbol        VARCHAR(10) UNIQUE NOT NULL,  -- e.g. 'GOLD', 'BTC', 'CASH'
  name          VARCHAR(255) NOT NULL,
  decimal_places INT NOT NULL DEFAULT 8,
  is_tradeable  BOOLEAN NOT NULL DEFAULT TRUE,
  is_cash       BOOLEAN NOT NULL DEFAULT FALSE,  -- TRUE only for the CASH asset
  created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### wallets
```sql
CREATE TABLE wallets (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id),
  asset_id          INT NOT NULL REFERENCES assets(id),
  available_balance DECIMAL(20, 8) NOT NULL DEFAULT 0,
  locked_balance    DECIMAL(20, 8) NOT NULL DEFAULT 0,
  created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, asset_id)
);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);
```

### orders
```sql
CREATE TABLE orders (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID NOT NULL REFERENCES users(id),
  asset_id         INT NOT NULL REFERENCES assets(id),
  side             VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
  type             VARCHAR(10) NOT NULL CHECK (type IN ('LIMIT', 'MARKET')),
  price            DECIMAL(20, 8),           -- NULL for MARKET orders
  quantity         DECIMAL(20, 8) NOT NULL,
  filled_quantity  DECIMAL(20, 8) NOT NULL DEFAULT 0,
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'PARTIAL', 'FILLED', 'CANCELLED')),
  created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  expires_at       TIMESTAMP                -- optional TTL for limit orders
);

CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_asset_status ON orders(asset_id, status);
```

### transactions
```sql
CREATE TABLE transactions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  buyer_id        UUID NOT NULL REFERENCES users(id),
  seller_id       UUID NOT NULL REFERENCES users(id),
  asset_id        INT NOT NULL REFERENCES assets(id),
  buyer_order_id  UUID NOT NULL REFERENCES orders(id),
  seller_order_id UUID NOT NULL REFERENCES orders(id),
  quantity        DECIMAL(20, 8) NOT NULL,
  price           DECIMAL(20, 8) NOT NULL,
  total_amount    DECIMAL(20, 8) NOT NULL,  -- quantity * price
  status          VARCHAR(10) NOT NULL DEFAULT 'COMPLETED'
                    CHECK (status IN ('COMPLETED', 'FAILED')),
  created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_buyer  ON transactions(buyer_id, created_at DESC);
CREATE INDEX idx_transactions_seller ON transactions(seller_id, created_at DESC);
CREATE INDEX idx_transactions_asset  ON transactions(asset_id, created_at DESC);
```

---

## 6. Redis Key Design

### Hot-Wallet Cache
```
Key:   wallet:{userId}:{assetId}
Value: JSON { "available": "123.45678900", "locked": "50.00000000" }
TTL:   5 minutes
Write policy: write-through (invalidate on any balance change)
```

### Order Book
```
Buy-side sorted set:
  Key:    orderbook:buy:{assetId}
  Score:  -1 * price  (negative so highest bid sorts first via ZRANGE)
  Member: orderId (string)

Sell-side sorted set:
  Key:    orderbook:sell:{assetId}
  Score:  +price  (lowest ask sorts first via ZRANGE)
  Member: orderId (string)

Order detail hash:
  Key:    orderbook:order:{orderId}
  Fields: userId, side, price, quantity, remainingQty, createdAt
```

### Distributed Locks (Redlock)
```
Key:   lock:wallet:{userId}:{assetId}
Value: random UUID (Redlock token for safe release)
TTL:   30 seconds
```

### Auth Token Blacklist
```
Key:   blacklist:token:{jti}
Value: 1
TTL:   matches JWT expiry
```

### Rate Limiting
```
Key:   ratelimit:{userId}:{endpoint}
Value: request count (INCR)
TTL:   60 seconds (sliding window)
```

---

## 7. API Endpoints

### Authentication
```
POST /api/auth/register    → Create account
POST /api/auth/login       → Returns JWT + refresh token
POST /api/auth/logout      → Blacklist current JWT
POST /api/auth/refresh     → Exchange refresh token for new JWT
```

### Wallets
```
GET  /api/wallets                      → All wallets for current user (portfolio)
GET  /api/wallets/{assetId}            → Single wallet balance
POST /api/wallets/{assetId}/deposit    → Add funds (admin or mock)
POST /api/wallets/{assetId}/withdraw   → Withdraw available balance
```

### Orders
```
POST   /api/orders                     → Place a new order
GET    /api/orders                     → List current user's orders (paginated)
GET    /api/orders/{orderId}           → Get single order details
DELETE /api/orders/{orderId}           → Cancel open order (releases locked balance)
GET    /api/assets/{assetId}/orderbook → View current order book depth (top N bids/asks)
```

### Transactions (Ledger)
```
GET /api/transactions                         → Current user's trade history (paginated)
GET /api/transactions/{transactionId}         → Single transaction details
GET /api/assets/{assetId}/transactions        → All trades for an asset
```

### Assets
```
GET  /api/assets             → List all tradeable assets
GET  /api/assets/{assetId}   → Asset details + current best bid/ask
POST /api/admin/assets       → Create new asset (ADMIN only)
PUT  /api/admin/assets/{id}  → Update asset (toggle is_tradeable) (ADMIN only)
```

---

## 8. Testing Requirements

### 8.1 Unit Tests (target: 60+ tests)
Focus: individual service methods with mocked dependencies

- [ ] `WalletService`: reserve balance, release balance, settle trade, insufficient balance error
- [ ] `WalletService`: Redis cache hit path, cache miss path, cache invalidation
- [ ] `OrderService`: validation — negative price, zero quantity, unknown asset, suspended asset
- [ ] `OrderService`: balance check for BUY vs SELL orders
- [ ] `MatchingEngine`: price-time priority logic with various bid/ask scenarios
- [ ] `MatchingEngine`: partial fill — correct remaining quantity calculation
- [ ] `MatchingEngine`: no match when bid < ask
- [ ] `TradeExecutionService`: atomic settlement — correct debit/credit on all four wallets
- [ ] `RedlockService`: lock acquisition, release, and TTL expiry behavior
- [ ] `TransactionLedger`: ledger entry fields and immutability

### 8.2 Integration Tests (target: 25+ tests)
Focus: full flows against a real test PostgreSQL + Redis (Testcontainers)

- [ ] Full trade lifecycle: register → deposit → place order → match → check wallets + ledger
- [ ] Partial fill: two orders with mismatched quantities, verify both order statuses
- [ ] Concurrent trade execution: N threads place orders on same asset simultaneously — verify no double-spend and correct final balances
- [ ] Redlock contention: simultaneous lock requests on same wallet — only one succeeds at a time
- [ ] Cache behaviour: balance read after deposit hits cache; balance read after cache expiry hits DB and re-caches
- [ ] Order cancellation: cancelled order releases locked balance correctly
- [ ] Insufficient balance: BUY order with insufficient CASH rejected; wallet unchanged
- [ ] Circuit breaker: orders on suspended asset are rejected
- [ ] Transaction rollback: simulate DB failure mid-settlement — verify wallets and ledger are unchanged
- [ ] Market order: executes immediately against existing limit orders; rejected if order book empty

**Total: 80+ unit and integration tests combined (matching resume claim)**

### 8.3 Financial Correctness Tests (subset of integration)
- [ ] Wealth conservation: sum of all user CASH + asset balances is identical before and after any trade
- [ ] Ledger reconciliation: sum of all `total_amount` in `transactions` matches total CASH that changed hands
- [ ] No negative balances: no wallet can have `available_balance < 0` or `locked_balance < 0` under any concurrent load

---

## 9. Implementation Phases

### Phase 1 — Foundation (Week 1–2)
- [ ] PostgreSQL schema and migrations (Flyway or Liquibase)
- [ ] User registration + JWT auth
- [ ] Basic wallet service with deposit/withdraw
- [ ] Wallet CRUD API
- [ ] Unit tests for wallet service

### Phase 2 — Order Book (Week 3–4)
- [ ] Redis Sorted Set order book with correct score encoding (negative for buy side)
- [ ] Order submission + validation
- [ ] Balance reservation on order placement
- [ ] Order cancellation + balance release
- [ ] Unit tests for order validation

### Phase 3 — Matching & Trade Execution (Week 5–6)
- [ ] Price-time priority matching engine
- [ ] Partial fill logic
- [ ] Atomic PostgreSQL settlement (all 4 wallet updates + ledger in one transaction)
- [ ] Integration test: full trade lifecycle

### Phase 4 — Distributed Locking (Week 7–8)
- [ ] Redisson Redlock on wallet operations
- [ ] Deadlock prevention via ordered lock acquisition
- [ ] Exponential backoff + 409 response on contention
- [ ] Concurrent trade tests: verify no double-spend

### Phase 5 — Caching & Performance (Week 9–10)
- [ ] Redis hot-wallet cache with write-through invalidation
- [ ] Portfolio endpoint served from Redis
- [ ] Measure and validate: 60% DB read reduction, 45% portfolio latency reduction
- [ ] Load test at 1000 trades/sec single-node

### Phase 6 — Testing & Hardening (Week 11–12)
- [ ] Reach 80+ total unit + integration tests
- [ ] Financial correctness tests (wealth conservation, no negative balances)
- [ ] Security: rate limiting, parameterized queries audit, JWT blacklist
- [ ] API documentation (Spring REST Docs or Swagger/OpenAPI)

---

## 10. Success Criteria

| Requirement | Target | Status |
|-------------|--------|--------|
| Trade execution latency | <5ms p99 | ❌ |
| Order book lookup | O(log N) — Redis Sorted Set | ❌ |
| PostgreSQL wallet read reduction | -60% via Redis cache | ❌ |
| Portfolio query latency reduction | -45% via Redis cache | ❌ |
| Double-spend prevention | Redlock on all wallet ops | ❌ |
| ACID compliance | Zero partial trades, zero data loss | ❌ |
| Test coverage | 80+ unit + integration tests | ❌ |
| Financial correctness | Wealth conserved across all trades | ❌ |

---

## 11. Risk Mitigation

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Double-spend under concurrency | Critical | Redis Redlock + ordered lock acquisition |
| Stale cache serving wrong balance | High | Write-through invalidation on every balance write |
| Deadlock on wallet lock acquisition | High | Always acquire locks in same alphabetical order |
| Partial settlement on DB failure | Critical | All wallet ops + ledger in single DB transaction |
| Redis Sorted Set score collision (same price) | Medium | Append timestamp to score as tiebreaker or use order insertion order |

---

## Appendix: Key Concepts

**Tokenized Asset** — A digital token representing real-world value. Trading these tokens is identical mechanically to trading stocks or commodities.

**Order Book** — Two sorted lists: bids (buyers, sorted highest price first) and asks (sellers, sorted lowest price first). A trade fires when the best bid ≥ best ask.

**Price-Time Priority** — When two orders share the same price, the older one matches first. The standard algorithm used by real exchanges (NYSE, NASDAQ).

**ACID** — Atomicity (all-or-nothing), Consistency (valid state preserved), Isolation (concurrent transactions don't interfere), Durability (committed data survives crashes). PostgreSQL provides all four.

**Double-Spend** — Spending the same balance in two concurrent transactions before either deducts it. Prevented here by acquiring a distributed lock on the wallet before reading or writing its balance.

**Redlock** — Dijkstra's mutual exclusion algorithm adapted for distributed systems. Requires acquiring a lock on a quorum of Redis nodes simultaneously. Prevents two processes from both believing they hold the lock.

**p99 Latency** — The 99th percentile response time. If p99 is 5ms, 99% of requests complete in ≤5ms; only 1% are slower.

**Hot Cache** — Frequently read data stored in Redis (RAM) rather than PostgreSQL (disk). Reads are ~0.1ms from Redis vs ~1–5ms from PostgreSQL under load.

**Write-Through Cache Invalidation** — When a value changes in PostgreSQL, immediately delete (or update) the Redis key. Next read re-populates from PostgreSQL. Ensures cache never serves stale data.
