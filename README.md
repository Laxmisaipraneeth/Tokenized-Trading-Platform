# Tokenized Asset Trading Platform

A backend trading engine (Java / Spring Boot) for buying and selling tokenized assets against
a cash balance — modeled on how a real exchange works: a Redis order book, price-time-priority
matching, atomic multi-wallet settlement, a double-entry ledger, distributed locking, and
cache-aside reads.

> **Status:** Core engine complete through Phase 5 (Redis caching + event-driven invalidation).
> See [PROGRESS.md](PROGRESS.md) for the phase-by-phase breakdown and [REQUIREMENTS.md](REQUIREMENTS.md)
> for the full spec.

---

## Table of contents

- [Tech stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Running the app](#running-the-app)
- [API walkthrough](#api-walkthrough)
- [Testing](#testing)
- [Project layout](#project-layout)
- [How a trade flows through the system](#how-a-trade-flows-through-the-system)
- [Troubleshooting](#troubleshooting)
- [Further reading](#further-reading)

---

## Tech stack

| Layer            | Choice                                            |
|------------------|---------------------------------------------------|
| Language/runtime | Java 25                                            |
| Framework        | Spring Boot 3.5.x (Web, Data JPA, Security, AOP)   |
| Database         | PostgreSQL 15 (source of truth)                    |
| Migrations       | Flyway                                             |
| Cache / book     | Redis 6+ via Redisson 3.27 (sorted sets, Redlock)  |
| Auth             | JWT (HS256)                                        |
| Build            | Maven (wrapper included — `./mvnw`)                |
| Tests            | JUnit 5, Testcontainers (Postgres + Redis), AssertJ |

---

## Prerequisites

You need these installed and running locally:

| Tool       | Version | Check                          |
|------------|---------|--------------------------------|
| JDK        | 25      | `java -version`                |
| PostgreSQL | 15      | `pg_isready -h localhost -p 5432` |
| Redis      | 6+      | `redis-cli ping` → `PONG`      |
| Docker     | any     | `docker info` — **only needed to run the integration tests** |

> Maven itself is **not** required — the repo ships the Maven wrapper (`demo/mvnw`).

---

## Quick start

```bash
# 1. Start Postgres and Redis (examples; use whatever you already run)
brew services start postgresql@15
brew services start redis

# 2. Create the database the app expects
createdb -h localhost -U postgres tokenized_trading

# 3. Build and run (Flyway auto-creates the schema + seeds assets on first boot)
cd demo
./mvnw spring-boot:run
```

The API is then live at **http://localhost:8080**. Flyway seeds four assets on first run:
`CASH` (id 1, the base currency), `GOLD` (id 2), `SLVR` (id 3), `REIT` (id 4).

---

## Configuration

All settings live in [demo/src/main/resources/application.yml](demo/src/main/resources/application.yml).
The defaults assume everything runs on localhost:

| Setting                    | Default                                  | Notes                              |
|----------------------------|------------------------------------------|------------------------------------|
| `spring.datasource.url`    | `jdbc:postgresql://localhost:5432/tokenized_trading` | DB must exist before boot |
| datasource user / password | `postgres` / `postgres`                  |                                    |
| `spring.data.redis.host/port` | `localhost:6379`                      |                                    |
| `app.jwt.secret`           | dev placeholder (≥ 32 chars)             | **replace in production**          |
| `app.jwt.expiration-ms`    | `3600000` (1 hour)                       |                                    |
| `app.cache.wallet-ttl-seconds` | `30`                                 | hot-wallet cache TTL               |
| `app.cache.portfolio-ttl-seconds` | `60`                              | portfolio cache TTL                |
| server port                | `8080` (Spring default; not overridden)  |                                    |

Override any value at runtime without editing the file, e.g.:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.datasource.password=mysecret
# or via environment:
SPRING_DATASOURCE_PASSWORD=mysecret ./mvnw spring-boot:run
```

Flyway runs migrations automatically on startup (`spring.flyway.enabled: true`); JPA is set to
`ddl-auto: validate`, so the entities are checked against the Flyway schema — they never mutate it.

---

## Running the app

```bash
cd demo

./mvnw spring-boot:run          # run in dev (foreground)
./mvnw clean package            # build an executable jar -> target/demo-0.0.1-SNAPSHOT.jar
java -jar target/demo-0.0.1-SNAPSHOT.jar   # run the jar
```

Health and metrics (Spring Actuator) are exposed at:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

---

## API walkthrough

Base path: `/api/v1`. Every endpoint except `/auth/**` requires a
`Authorization: Bearer <jwt>` header. A full buy/sell flow with `curl`:

```bash
BASE=http://localhost:8080/api/v1

# 1. Register (returns a JWT). Do this for a buyer and a seller.
curl -s -X POST $BASE/auth/register -H 'Content-Type: application/json' -d '{
  "email": "buyer@example.com", "password": "password123", "fullName": "Buyer"
}'
# -> { "token": "...", "userId": "...", "email": "...", "role": "TRADER" }

# 2. Save the token and use it on every call below
TOKEN="paste-jwt-here"
AUTH="Authorization: Bearer $TOKEN"

# 3. Create a CASH wallet (asset id 1) and fund it
curl -s -X POST $BASE/wallets/1 -H "$AUTH"
curl -s -X POST $BASE/wallets/1/deposit -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{ "amount": 100000 }'

# 4. Place a BUY order for 10 GOLD (asset id 2) @ 100
curl -s -X POST $BASE/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "assetId": 2, "side": "BUY", "price": 100, "quantity": 10
}'

# 5. Inspect state
curl -s $BASE/wallets/portfolio -H "$AUTH"      # cached portfolio
curl -s $BASE/orderbook/GOLD     -H "$AUTH"      # live order book depth
curl -s $BASE/orders/open        -H "$AUTH"      # my open orders
curl -s $BASE/trades             -H "$AUTH"      # my trade history
```

### Endpoint reference

| Method & path                                | Purpose                                  | Auth |
|----------------------------------------------|------------------------------------------|------|
| `POST /auth/register`                        | Create account, returns JWT              | no   |
| `POST /auth/login`                           | Log in, returns JWT                      | no   |
| `GET /assets`                                | List tradeable assets                    | yes  |
| `GET /assets/{id}`                           | Single asset                             | yes  |
| `GET /wallets`                               | All my wallets (DB-direct)               | yes  |
| `GET /wallets/portfolio`                     | My portfolio (cached, 60s)               | yes  |
| `GET /wallets/{assetId}`                     | One wallet (cached, 30s)                 | yes  |
| `POST /wallets/{assetId}`                    | Create a wallet for an asset             | yes  |
| `POST /wallets/{assetId}/deposit`            | Deposit funds                            | yes  |
| `POST /wallets/{assetId}/withdraw`           | Withdraw funds                           | yes  |
| `GET /wallets/{assetId}/transactions`        | Ledger history (paged)                   | yes  |
| `POST /orders`                               | Place a limit order (BUY/SELL)           | yes  |
| `DELETE /orders/{orderId}`                   | Cancel an order, release reserved funds  | yes  |
| `GET /orders`                                | My order history (paged)                 | yes  |
| `GET /orders/open`                           | My open / partially-filled orders        | yes  |
| `GET /orderbook/{symbol}`                    | Order book depth for a symbol            | yes  |
| `GET /trades`                                | My trade history (paged)                 | yes  |
| `GET /trades/order/{orderId}`                | Fills for one of my orders               | yes  |
| `GET /trades/asset/{assetId}`                | Public trade history for an asset        | yes  |

---

## Testing

There are two tiers of tests. **The integration tests require Docker to be running** — they spin
up throwaway Postgres and Redis containers via Testcontainers, so they never touch your local
`tokenized_trading` database or dev Redis.

```bash
cd demo

# Make sure Docker is up first (macOS):
open -a Docker          # then wait until `docker info` succeeds

# Run the whole suite
./mvnw test

# Run only the financial-correctness invariant suite
./mvnw test -Dtest='WealthConservationTest,DoubleSpendConcurrencyTest,LedgerReconciliationTest'

# Run a single test class
./mvnw test -Dtest=DoubleSpendConcurrencyTest
```

### Financial-correctness invariants

The most important tests live in
[demo/src/test/java/.../invariants/](demo/src/test/java/com/tokenizedtradingplatform/demo/invariants/)
and assert the three properties a money system must never violate:

| Test class                     | Invariant proven                                                                 |
|--------------------------------|----------------------------------------------------------------------------------|
| `WealthConservationTest`       | Trades only transfer value. Total supply of every asset is identical before/after. |
| `DoubleSpendConcurrencyTest`   | 8 buyers hitting one 10-unit ask at once sell **exactly** 10 — no over-fill, no negative balances. Exercises the pessimistic row lock + stale-status check in the matching engine under real contention. |
| `LedgerReconciliationTest`     | Every wallet's balance equals Σ(credits) − Σ(debits); every trade posts exactly 4 balanced double-entry rows. |

They share a singleton-container harness in `AbstractFinancialInvariantTest` (containers start
once and are reused across all test classes, because the classes share one cached Spring context).

> **No Docker?** The integration tests fail fast with
> `Could not find a valid Docker environment`. Start Docker and re-run — nothing else is needed.

---

## Project layout

```
demo/src/main/java/com/tokenizedtradingplatform/demo/
├── api/
│   ├── controller/      REST endpoints (Auth, Wallet, Order, Trade, Asset)
│   ├── request/         request DTOs (records / @Data)
│   └── response/        response DTOs
├── application/
│   ├── service/         business logic — OrderService, MatchingEngine,
│   │                    TradeSettlementService, WalletService, AuthService, ...
│   └── event/           domain events + AFTER_COMMIT listeners (cache + book)
├── domain/
│   ├── model/           JPA entities (User, Asset, Wallet, Order, Trade, Transaction)
│   ├── repository/      Spring Data repositories (incl. pessimistic-lock queries)
│   └── enums/           OrderSide, OrderStatus, OrderType, TransactionType, UserRole
├── infrastructure/
│   ├── OrderBookRedisAdapter   Redis sorted sets (the order book)
│   ├── cache/                  wallet + portfolio cache-aside adapters
│   └── lock/                   @DistributedLock annotation + Redlock aspect
├── security/            JWT util + auth filter
├── config/              Spring Security config
└── exception/           custom exceptions + global handler

demo/src/main/resources/
├── application.yml      all configuration
└── db/migration/        Flyway migrations V1–V6 (+ asset seed data in V2)

demo/src/test/java/.../invariants/   financial-correctness integration tests
```

---

## How a trade flows through the system

```
POST /api/v1/orders
  └─ @DistributedLock("lock:trade:execute:#{userId}")   ← per-user Redlock, held across the txn
       └─ @Transactional
            ├─ validate asset is tradeable
            ├─ reserve funds: available → locked  (BUY reserves cash, SELL reserves the asset)
            ├─ persist the order
            └─ MatchingEngine.match()
                 └─ loop while remaining > 0:
                      ├─ peek best opposing order from Redis sorted set
                      ├─ findByIdWithLock(...)            ← pessimistic lock = no double-spend
                      ├─ skip if stale (cancelled/filled)
                      ├─ price-cross check
                      └─ TradeSettlementService.settle()  ← atomic 4-wallet move + 4 ledger rows
       └─ on COMMIT (AFTER_COMMIT listeners):
            ├─ invalidate wallet + portfolio caches
            └─ ZADD any unfilled remainder to the Redis book  (never leaves a phantom order)
```

Key guarantees: all balance moves and ledger writes happen in **one DB transaction** (atomic),
the pessimistic order lock prevents concurrent fills from over-selling, and Redis is only mutated
**after** the DB commits so a rollback can never leave a stale book entry or stale cache.

---

## Troubleshooting

| Symptom                                              | Cause / fix                                                                 |
|------------------------------------------------------|-----------------------------------------------------------------------------|
| `Could not find a valid Docker environment` (tests)  | Docker isn't running. `open -a Docker`, wait for `docker info` to succeed.  |
| App won't start: `Connection refused :5432`          | Postgres isn't running, or `tokenized_trading` DB doesn't exist. `createdb`.|
| App won't start: Redis connection error              | Redis isn't running. `redis-cli ping` should return `PONG`.                 |
| Flyway `validate` / migration checksum error         | Schema drift. For a clean dev reset: `dropdb tokenized_trading && createdb tokenized_trading`. |
| `401 Unauthorized` on every call                     | Missing/expired JWT. Re-`login` and send `Authorization: Bearer <token>`.   |
| `400 Insufficient balance` placing an order          | Fund the wallet first; BUY reserves `price × qty` cash, SELL reserves the asset qty. |
| Order placed but never matches                       | No crossing order in the book, or wrong symbol/price. Check `GET /orderbook/{symbol}`. |

---

## Further reading

- [REQUIREMENTS.md](REQUIREMENTS.md) — full functional & non-functional spec
- [PLAN.md](PLAN.md) — architecture and phased implementation plan
- [PROGRESS.md](PROGRESS.md) — what's built, what's pending
- [SPRING_GUIDE.md](SPRING_GUIDE.md) — Spring Boot concepts as used in this project
- [ANNOTATIONS_GUIDE.md](ANNOTATIONS_GUIDE.md) — annotation-by-annotation walkthrough
```
