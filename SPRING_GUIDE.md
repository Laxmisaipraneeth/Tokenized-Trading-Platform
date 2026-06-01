# Spring Boot Concepts — A Tour of This Project

Things you said you already know (skipped here):
`@RestController`, `@Component`, `@Bean`, `@Service`, `@PathVariable`,
`@RequestMapping`, basic dependency injection.

This guide covers everything *else* that shows up in this codebase, in order
from the big picture down to the small details.

---

## 1. The 30,000-foot view — what is Spring Boot actually doing?

A Spring Boot app is a **container of beans** plus a bunch of **auto-configured
infrastructure**. When the app starts:

1. `@SpringBootApplication` triggers component scanning — Spring walks every
   class under your base package looking for `@Component`, `@Service`,
   `@Repository`, `@Controller`, `@Configuration`.
2. Each one becomes a **bean** — a singleton object Spring manages.
3. Spring resolves their dependencies (constructor params, `@Autowired` fields)
   and wires them together.
4. **Auto-configuration** kicks in: it sees you have `spring-boot-starter-web`
   on the classpath → starts Tomcat. Sees `spring-boot-starter-data-jpa` →
   creates a Hibernate `EntityManager`, transaction manager, connection pool.
   Sees `redisson-spring-boot-starter` → creates a `RedissonClient` from your
   `spring.data.redis.*` properties.
5. Your code is the small bit of glue on top of all that.

The flow of a request in this project:

```
HTTP request
    ↓
JwtAuthenticationFilter            (security/ — sets auth context)
    ↓
DispatcherServlet                  (Spring routes by URL)
    ↓
@RestController method             (api/controller/ — translates HTTP)
    ↓
@Service method                    (application/service/ — orchestrates)
    ↓ ↘
JpaRepository      OrderBookRedisAdapter
    ↓                    ↓
PostgreSQL           Redis
```

---

## 2. The persistence layer — JPA + Hibernate

JPA is a **specification**. Hibernate is the **implementation** Spring Boot
uses. JPA annotations let you describe how a Java class maps to a database
table.

### `@Entity` and `@Table`
Marks a class as a row in a database table.

```java
@Entity
@Table(name = "orders")
public class Order { ... }
```
Without `@Table`, Hibernate guesses the table name from the class name.
We use `@Table` explicitly because `order` is a SQL reserved word.

### `@Id`, `@GeneratedValue`
`@Id` marks the primary key. `@GeneratedValue` says "let the DB or JPA fill
this in":
```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;
```
Strategies you'll see:
- `UUID` — JPA generates a UUID before insert
- `IDENTITY` — uses DB auto-increment (SERIAL, AUTO_INCREMENT)
- `SEQUENCE` — uses a DB sequence object

### `@Column`
Tunes the column mapping:
```java
@Column(nullable = false, precision = 28, scale = 8)
private BigDecimal price;
```
- `precision`/`scale` are for `DECIMAL(28,8)` — 28 total digits, 8 after the point
- `length` for VARCHAR
- `name` if the column name differs from the field name

### `@ManyToOne`, `@JoinColumn`, `FetchType`
Relationships between entities:
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;
```
- `@ManyToOne` — many orders belong to one user
- `@JoinColumn` — the foreign key column name in the `orders` table
- `FetchType.LAZY` — don't load the User object until you actually call
  `order.getUser()`. The default for `@ManyToOne` is EAGER, which can blow up
  query counts (the **N+1 problem**). Always prefer LAZY.

### `@Enumerated(EnumType.STRING)`
How to store Java enums in the DB:
```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 4)
private OrderSide side;   // BUY or SELL
```
`STRING` stores `"BUY"`. The alternative `ORDINAL` stores `0`/`1` — never use
it because reordering the enum silently corrupts data.

### `@CreationTimestamp`, `@UpdateTimestamp`
Hibernate-specific (not JPA standard). Auto-fills timestamps on insert/update:
```java
@CreationTimestamp private Instant createdAt;
@UpdateTimestamp   private Instant updatedAt;
```

### `@Version` — optimistic locking
```java
@Version
private Long version;
```
Hibernate adds `WHERE version = ?` to updates and bumps the column. If two
transactions try to update the same row, one of them gets an
`OptimisticLockException` and can retry. We use this as a **backup** in
`Wallet`; the primary defense is pessimistic locking (next section).

### Why we use `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
JPA *requires* a no-arg constructor for reflection. But we don't want random
code calling `new Order()` — we want everyone to use the builder. So we make
the no-arg constructor protected: visible to JPA's proxy subclasses, hidden
from your code.

---

## 3. The repository layer — Spring Data JPA

`JpaRepository<Entity, IdType>` is an interface that gives you free methods:
`save()`, `findById()`, `findAll()`, `delete()`, `count()`, paging.

You don't write the implementation — Spring Data generates it at startup by
parsing the interface.

### Derived query methods
Spring parses the *method name* into SQL:
```java
Optional<Wallet> findByUserIdAndAssetId(UUID userId, Integer assetId);
```
becomes
```sql
SELECT * FROM wallets WHERE user_id = ? AND asset_id = ?
```
The grammar: `findBy` + Field + (`And`/`Or`) + Field + (`OrderBy`...).

### `@Query` — when derived methods aren't enough
```java
@Query("SELECT o FROM Order o JOIN FETCH o.asset WHERE o.user.id = :userId")
List<Order> findByUserIdAndStatusIn(@Param("userId") UUID userId, ...);
```
- This is **JPQL**, not SQL. You query *entities and fields*, not tables and
  columns.
- `JOIN FETCH` — load the related entity in the same query (avoids N+1).
- `:userId` — named parameter, bound by `@Param`.

### `@Lock(LockModeType.PESSIMISTIC_WRITE)` — pessimistic locking

**Simple version:** without a lock, two transactions can both read a
wallet's `balance=100`, both subtract `50`, both write `50`. You just
gave away `50` for free — the **lost-update** bug. A pessimistic write
lock makes the second reader **wait** until the first transaction commits.

```sql
-- Without lock:
T1: SELECT balance FROM wallets WHERE id=X         → reads 100
T2: SELECT balance FROM wallets WHERE id=X         → reads 100
T1: UPDATE wallets SET balance=50 WHERE id=X       → balance is now 50
T2: UPDATE wallets SET balance=50 WHERE id=X       → still 50 (bug!)

-- With SELECT FOR UPDATE on T1:
T1: SELECT ... FOR UPDATE WHERE id=X               → reads 100, locks row
T2: SELECT ... FOR UPDATE WHERE id=X               → BLOCKS, waits
T1: UPDATE ... balance=50, COMMIT                  → row unlocks
T2: SELECT ... reads 50, deducts to 0              → correct
```

Spring Data JPA gives you `FOR UPDATE` via `@Lock`:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.id = :id")
Optional<Wallet> findByIdWithLock(@Param("id") UUID id);
```

**In this project:**
Every wallet write uses the locked finder. See
[WalletService.deposit()](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/WalletService.java):
```java
Wallet wallet = walletRepository.findByUserIdAndAssetIdWithLock(userId, assetId)...
wallet.credit(amount);
walletRepository.save(wallet);
```
The Redlock layer in Phase 4 sits *on top* of this for cross-process
coordination; the row-level `FOR UPDATE` is the inner ring of defense.

### `Pageable` and `Page<T>`
Pagination support out of the box:
```java
Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
```
The controller takes `Pageable` as a parameter and Spring auto-binds query
params: `?page=0&size=20&sort=createdAt,desc`. The returned `Page<T>` has
`.getContent()`, `.getTotalElements()`, `.getNumber()`, etc.

---

## 4. Transactions — `@Transactional`

A transaction is a **unit of work** in the database. Either everything
commits, or everything rolls back.

```java
@Transactional
public WalletResponse deposit(UUID userId, Integer assetId, DepositRequest req) {
    Wallet wallet = walletRepository.findByUserIdAndAssetIdWithLock(...);
    wallet.credit(req.getAmount());
    walletRepository.save(wallet);
    recordTransaction(wallet, ...);
}
```

Spring wraps this method in a proxy: opens a DB transaction before calling,
commits after returning, rolls back on `RuntimeException`. This is
**AOP** (aspect-oriented programming) — covered in detail in section 5a.

Important details:

- **`@Transactional(readOnly = true)`** — hint to Hibernate to skip dirty
  checking and optimize for reads. We use this on all `getXxx` methods.
- **Rollback rules** — by default, Spring rolls back only on
  `RuntimeException` and `Error`, *not* checked exceptions. Use
  `rollbackFor = Exception.class` if you have checked exceptions you want
  to trigger rollback.
- **Self-invocation gotcha** — `@Transactional` works because Spring wraps
  your bean in a proxy. If a method in the same class calls another
  `@Transactional` method via `this.foo()`, the proxy is bypassed and the
  annotation is ignored. Always cross a Spring bean boundary.

### `@TransactionalEventListener(AFTER_COMMIT)`
Fires *only after the transaction successfully commits*. Perfect for
side-effects that must not happen if the DB rolled back: cache invalidation,
publishing to Redis, sending notifications. Covered in detail in §5b.

---

## 5. Security — Spring Security + JWT

Spring Security inserts a **filter chain** in front of every request. Each
filter can decide: pass through, reject, or attach user info to the context.

### `SecurityFilterChain` (replaces the old `WebSecurityConfigurerAdapter`)
In [SecurityConfig.java](demo/src/main/java/com/tokenizedtradingplatform/demo/config/SecurityConfig.java):
```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())                          // no CSRF for stateless API
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/auth/**").permitAll()    // login/register open
            .anyRequest().authenticated())                     // everything else needs JWT
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
}
```

### `OncePerRequestFilter` — our JWT filter
A guarantee: this filter runs once per HTTP request (vs. once per dispatch,
which can include forwards/includes). In
[JwtAuthenticationFilter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtAuthenticationFilter.java):
1. Read the `Authorization: Bearer <token>` header
2. Validate the JWT with `JwtUtil`
3. Build a `UsernamePasswordAuthenticationToken` with `userId` as principal
4. Stash it in `SecurityContextHolder` for the rest of the request

### `@AuthenticationPrincipal`

**Simple version:** the JWT filter stores something in a thread-local
`SecurityContext` before the controller runs. `@AuthenticationPrincipal`
fetches that something and injects it as a method parameter — no manual
context lookups in business code.

```java
// In a filter (runs first):
SecurityContextHolder.getContext().setAuthentication(
    new UsernamePasswordAuthenticationToken(userId, null, authorities));

// In a controller (runs later, same request):
@GetMapping("/me")
public Whatever me(@AuthenticationPrincipal UUID userId) {
    return ...;
}
```

The type of the injected parameter must match whatever the filter
stored as the `principal`. We store a `UUID`, so controllers declare
`UUID userId`.

**In this project:**
[WalletController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/WalletController.java):
```java
@GetMapping
public ResponseEntity<List<WalletResponse>> getAllWallets(
        @AuthenticationPrincipal UUID userId) {
    return ResponseEntity.ok(walletService.getAllWallets(userId));
}
```
The `userId` comes from
[JwtAuthenticationFilter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtAuthenticationFilter.java)
which set the principal earlier in the request:
```java
new UsernamePasswordAuthenticationToken(
    jwtUtil.extractUserId(token), null, ...);
```

### `BCryptPasswordEncoder(12)`
Hashes passwords with bcrypt at strength 12 (2^12 rounds). Used in
`AuthService.register()` and `login()`. Never store plaintext.

### Why we exclude `UserDetailsServiceAutoConfiguration`
By default Spring Security creates an in-memory user with a random password
printed to the console. We don't want that — our auth comes from JWT, not
form login. Excluding it silences the warning and prevents a useless bean.

---

## 5a. AOP — aspect-oriented programming

Two of the things Spring does for you in this project are implemented via
**AOP**: `@Transactional` and our `@DistributedLock`.

**The idea**: there's behaviour you want around a method ("start a
transaction before, commit after," "acquire a lock before, release after")
that has nothing to do with the method's business logic. Putting that
code into every method clutters them. AOP lets you *separately declare*
the behaviour and apply it to methods by name pattern, annotation, etc.

### Three pieces of AOP vocabulary

- **Aspect** — a class holding the cross-cutting code.
- **Pointcut** — the rule that says *which* methods to apply it to.
- **Advice** — the code that runs (before / after / around the matched
  method).

In `DistributedLockAspect`:

```java
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class DistributedLockAspect {

    @Around("@annotation(distributedLock)")     // ← pointcut
    public Object around(ProceedingJoinPoint pjp,
                         DistributedLock distributedLock) throws Throwable {  // ← advice
        // before
        RLock lock = lockService.acquire(...);
        try {
            return pjp.proceed();               // ← call the original method
        } finally {
            lockService.release(lock);          // ← after
        }
    }
}
```

### How Spring makes it work

Spring inspects every bean at startup. If a bean has methods matched by
some advice, Spring replaces the bean with a **proxy** that intercepts
calls and runs the advice. This is why **self-invocation breaks AOP** —
calling `this.foo()` from another method in the same class bypasses the
proxy and skips the advice.

### Ordering when multiple aspects apply

Both `@Transactional` (Spring) and `@DistributedLock` (us) apply to
`OrderService.placeOrder()`. Lower `@Order` value = outer wrapping.
`@Transactional` defaults to `LOWEST_PRECEDENCE` (a very high number);
our aspect uses `HIGHEST_PRECEDENCE + 100` (much lower), so:

```
[Aspect: DistributedLock]    acquire(lock)
  [Aspect: Transactional]    begin tx
    [method body]            …work…
  [Aspect: Transactional]    commit tx
[Aspect: DistributedLock]    release(lock)
```

This is the order we want: lock is held for the *entire* transaction,
including commit. If it were inverted, another thread could acquire the
lock and read pre-commit state. Section 22 covers the lock subsystem
in detail.

---

## 5b. Application events — decoupled side-effects

Spring has a built-in **event bus**. Any bean can publish an event; any
bean can listen for it. The publisher doesn't know who's listening, and
new listeners don't require touching the publisher. This is how Phase 5
keeps cache invalidation out of the wallet/order service code.

### Simple example

A publisher:
```java
@Service
@RequiredArgsConstructor
public class GreetingService {
    private final ApplicationEventPublisher events;

    public void greet(String name) {
        // ... business logic ...
        events.publishEvent(new GreetedEvent(name));
    }
}
```

The event itself (a record is fine):
```java
public record GreetedEvent(String name) {}
```

A listener — totally separate class, no reference from the publisher:
```java
@Component
public class GreetingLogger {
    @EventListener
    public void onGreeted(GreetedEvent e) {
        System.out.println("Hi, " + e.name());
    }
}
```

When `greetingService.greet("Alice")` runs, Spring invokes
`GreetingLogger.onGreeted` synchronously, in the same thread.

### `@EventListener` vs `@TransactionalEventListener`

`@EventListener` fires *immediately* when `publishEvent` is called — even
if you're inside a `@Transactional` method and the transaction later rolls
back. That's bad for anything you *don't want to happen* on rollback.

`@TransactionalEventListener` defers the listener until a specified phase
of the surrounding transaction:

| Phase | When it fires |
|---|---|
| `BEFORE_COMMIT` | Just before commit. Listener can throw to abort the commit. |
| `AFTER_COMMIT` *(default)* | After the transaction has successfully committed. |
| `AFTER_ROLLBACK` | After the transaction rolled back. |
| `AFTER_COMPLETION` | After the transaction ends, either way. |

If you publish an event *outside* a transaction and the listener uses
`@TransactionalEventListener`, **the listener never runs** (no transaction
to attach to). Set `fallbackExecution = true` to fire anyway.

### The cache-invalidation problem this solves

Naive approach — invalidate inline:
```java
@Transactional
public void deposit(...) {
    wallet.credit(amount);
    walletRepository.save(wallet);
    walletCache.invalidate(userId, assetId);  // ← BUG
}
```
If the DB save commits but throws *after* (e.g., a downstream JPA flush
failure), the cache is gone but the DB still has the old value. Worse,
if the transaction rolls back, the cache is gone but DB still has the
*pre-deposit* value. Next read repopulates the cache with stale data.

Correct approach — invalidate after commit:
```java
@Transactional
public void deposit(...) {
    wallet.credit(amount);
    walletRepository.save(wallet);
    events.publishEvent(new WalletBalanceChangedEvent(userId, assetId));
}

@Component
class WalletEventListener {
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onChange(WalletBalanceChangedEvent e) {
        walletCache.invalidate(e.userId(), e.assetId());
    }
}
```
The listener only runs when the DB is guaranteed committed. Rollback →
no listener → cache untouched → still consistent with DB.

### In this project

[WalletService.deposit()](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/WalletService.java):
```java
eventPublisher.publishEvent(new WalletBalanceChangedEvent(userId, assetId));
```

[WalletEventListener.onBalanceChanged()](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/WalletEventListener.java):
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onBalanceChanged(WalletBalanceChangedEvent event) {
    walletCache.invalidate(event.userId(), event.assetId());
    portfolioCache.invalidate(event.userId());
}
```

Same pattern wires `OrderRestedEvent` → `OrderBookEventListener` so a
new resting order only appears in the Redis sorted set *after* the
placing transaction commits.

---

## 6. The HTTP layer — request/response handling

### `@RequestBody` + `@Valid`
```java
public ResponseEntity<...> place(@Valid @RequestBody PlaceOrderRequest req) {...}
```
- `@RequestBody` — deserialize the JSON body into the DTO with Jackson.
- `@Valid` — run bean validation (`@NotNull`, `@DecimalMin`, etc.) on the DTO.
  If validation fails, Spring throws `MethodArgumentNotValidException`, which
  our `GlobalExceptionHandler` catches and returns as 400.

### `ResponseEntity<T>`
A wrapper that lets you control status code and headers, not just the body:
```java
return ResponseEntity.status(HttpStatus.CREATED).body(order);
return ResponseEntity.ok(walletService.getWallet(...));
```

### `@RestControllerAdvice` + `@ExceptionHandler`
Global exception handling:
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handle(InsufficientFundsException e) {
        return ResponseEntity.status(422).body(...);
    }
}
```
Any controller throws `InsufficientFundsException` → this handler runs →
client gets a clean JSON error with the right status code. No try/catch
clutter in controllers.

### Records as DTOs
Java 16+ feature we use heavily:
```java
public record PlaceOrderRequest(
    @NotNull Integer assetId,
    @NotNull OrderSide side,
    @NotNull @DecimalMin("0.00000001") BigDecimal price,
    @NotNull @DecimalMin("0.00000001") BigDecimal quantity
) {}
```
This generates: constructor, accessors (`assetId()`, `side()`, ...),
`equals`, `hashCode`, `toString`. All fields are final. Perfect for DTOs.

---

## 7. Lombok — boilerplate killer

Lombok is a compile-time annotation processor. The annotations get expanded
into real Java code before javac runs.

| Annotation | What it generates |
|---|---|
| `@Getter` / `@Setter` | accessor methods for every field |
| `@NoArgsConstructor` | `public Foo() {}` |
| `@AllArgsConstructor` | constructor with every field |
| `@RequiredArgsConstructor` | constructor with `final` and `@NonNull` fields only |
| `@Builder` | fluent builder pattern |
| `@Data` | shortcut for `@Getter @Setter @ToString @EqualsAndHashCode @RequiredArgsConstructor` |

Why this matters in this project:

### `@RequiredArgsConstructor` on services and controllers
```java
@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    // ...no constructor written by hand
}
```
Lombok generates a constructor with both `final` fields. Spring's constructor
injection picks it up. Result: zero `@Autowired`, immutable dependencies,
trivial to mock in tests.

### `@Builder` and `@Builder.Default`
```java
Wallet.builder().user(u).asset(a).build();
```
For fields with defaults, `@Builder` ignores the field initializer unless
you mark it `@Builder.Default`:
```java
@Builder.Default
private BigDecimal availableBalance = BigDecimal.ZERO;
```
Without `@Builder.Default`, `Wallet.builder().build()` would leave the field
null even though you wrote `= BigDecimal.ZERO`.

### `AccessLevel.PROTECTED` for JPA
```java
@NoArgsConstructor(access = AccessLevel.PROTECTED)
```
JPA needs the no-arg constructor; you don't want app code calling it.

---

## 8. Configuration — `application.yml`

Spring Boot's convention: properties in `application.yml` (or `.properties`)
get bound to beans. Three flavors you'll see:

### Auto-configured properties (Spring owns these keys)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tokenized_trading
    username: postgres
  jpa:
    hibernate:
      ddl-auto: validate
```
Spring Boot's auto-config reads these and configures Hibernate, HikariCP,
etc. You don't define beans for them.

### `ddl-auto` — important to understand
- `none` — Hibernate doesn't touch schema
- `validate` — Hibernate verifies entities match existing tables, fails fast
  on mismatch (this is what we use)
- `update` — Hibernate adds missing columns/tables (dangerous in prod)
- `create` / `create-drop` — wipes everything (test only)

We use `validate` because **Flyway owns the schema**. Hibernate just checks
its mapping is correct.

### Custom properties
```yaml
app:
  jwt:
    secret: "..."
    expiration-ms: 3600000
```
Read with `@Value("${app.jwt.secret}")` or with a typed
`@ConfigurationProperties` class.

### Profile-specific config
`application-dev.yml`, `application-prod.yml` — selected via
`SPRING_PROFILES_ACTIVE=prod`. Override anything per environment.

---

## 9. Database tooling — Flyway, HikariCP

### Flyway
Versioned schema migrations. Files in `src/main/resources/db/migration/`
following the pattern `V{n}__{description}.sql`:
```
V1__create_users.sql
V2__create_assets.sql
V3__create_wallets.sql
V4__create_transactions.sql
V5__create_orders.sql
```
On startup Flyway:
1. Looks at `flyway_schema_history` table
2. Finds which versions haven't been applied
3. Runs them in order

Once a migration is applied, **never edit it** — Flyway records its checksum
and will refuse to start if it changes. Always add a new `V{n+1}` migration.

### HikariCP
The connection pool Spring Boot uses by default. Maintains a fixed set of
DB connections so each request reuses one instead of opening a new TCP
socket.
```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
```

---

## 10. Redis layer — Redisson

Spring Data Redis (with Lettuce) is the default Redis client in Spring Boot.
We use **Redisson** instead because we need its distributed lock
implementation (`RLock`) for Phase 4 Redlock.

`redisson-spring-boot-starter` reads the same `spring.data.redis.*`
properties and registers a `RedissonClient` bean. We inject it directly:
```java
@Component
@RequiredArgsConstructor
public class OrderBookRedisAdapter {
    private final RedissonClient redissonClient;

    public void addOrder(...) {
        RScoredSortedSet<String> zset =
            redissonClient.getScoredSortedSet("orderbook:GOLD:bids");
        zset.add(score, orderId);
    }
}
```
Each Redis data type has a corresponding Redisson interface:
`RBucket` (string), `RMap` (hash), `RList`, `RScoredSortedSet`, `RLock`.

---

## 10a. Cache-aside pattern + Micrometer metrics

A **cache-aside** cache sits beside your database, not in front of it.
The service code looks up the cache first; on a miss, it queries the DB
and *writes the result into the cache* before returning. Writes go to
the DB and **invalidate** (not update) the cache. Next read repopulates.

### Simple example

```java
public Wallet getWallet(UUID userId, Integer assetId) {
    return cache.get(userId, assetId).orElseGet(() -> {
        Wallet fresh = walletRepository.findByUserIdAndAssetId(...);
        cache.put(userId, assetId, fresh);
        return fresh;
    });
}
```

### Why invalidate instead of update?

Imagine two threads do simultaneous deposits. Both write to the DB
(serialized by the row lock), both then "update" the cache. Whichever
write reaches the cache last wins, but that may not reflect the actual
order of DB commits. Race-prone.

Invalidating instead: both threads delete the same key. Next read pulls
the truth from the DB. No race.

### Metrics — proving the resume claim

Micrometer's `Counter` is a monotonically increasing tally. Increment on
every hit and miss; the *hit rate* is `hits / (hits + misses)`. With
`spring-boot-starter-actuator` exposing the `metrics` endpoint, you can
GET `/actuator/metrics/wallet.cache.hit` for live numbers.

```java
hitCounter  = Counter.builder("wallet.cache.hit").register(meterRegistry);
missCounter = Counter.builder("wallet.cache.miss").register(meterRegistry);
// ...
if (cached != null) { hitCounter.increment(); ... }
else                { missCounter.increment(); ... }
```

### In this project

[WalletService.getWallet()](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/WalletService.java):
```java
return walletCache.get(userId, assetId).orElseGet(() -> {
    Wallet wallet = walletRepository.findByUserIdAndAssetId(userId, assetId)
            .orElseThrow(() -> new ResourceNotFoundException(...));
    WalletResponse response = toResponse(wallet);
    walletCache.put(userId, assetId, response);
    return response;
});
```

Live-measured after 6 serial reads of the same wallet:
```json
GET /actuator/metrics/wallet.cache.hit  → { "value": 5.0 }
GET /actuator/metrics/wallet.cache.miss → { "value": 1.0 }
```
83% hit rate, well past the resume's 60% target.

---

## 11. Putting it all together — a request walkthrough

`POST /api/v1/orders` with body `{"assetId":2,"side":"BUY","price":100,"quantity":1}`:

1. **Tomcat** receives the HTTP request.
2. **Spring Security filter chain** runs. `JwtAuthenticationFilter` reads
   the `Authorization` header, validates the JWT, sets the userId in the
   `SecurityContext`.
3. **DispatcherServlet** matches the URL to `OrderController.placeOrder`.
4. Spring deserializes the JSON body to `PlaceOrderRequest` (Jackson),
   runs validation (`@Valid`), and injects `@AuthenticationPrincipal UUID
   userId` from the security context.
5. The controller calls `orderService.placeOrder(userId, req)`.
6. `@Transactional` opens a DB transaction.
7. `userRepository.findById` — Spring Data JPA fires a SELECT.
8. `assetRepository.findById` — another SELECT.
9. `walletRepository.findByUserIdAndAssetIdWithLock` — `SELECT ... FOR
   UPDATE`. The wallet row is now locked for the rest of the transaction.
10. `wallet.reserve(amount)` — moves money from `availableBalance` to
    `lockedBalance` *in memory*.
11. `walletRepository.save(wallet)` — Hibernate flushes an UPDATE.
12. `orderRepository.saveAndFlush(order)` — INSERT, with `id` and
    `createdAt` filled in.
13. `orderBook.addOrder(...)` — Redisson `ZADD` on
    `orderbook:GOLD:bids`.
14. Method returns. `@Transactional` proxy commits the DB transaction. The
    wallet lock releases.
15. Controller wraps the result in `ResponseEntity.status(CREATED).body(...)`.
16. Spring serializes the response DTO to JSON, sends `201` back.

---

## 12. Where to read more

- [Spring Framework reference](https://docs.spring.io/spring-framework/reference/) — IoC, AOP, transactions
- [Spring Boot reference](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/) — auto-configuration, properties
- [Spring Data JPA reference](https://docs.spring.io/spring-data/jpa/reference/) — derived queries, `@Query`, locking
- [Hibernate User Guide](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html) — fetch strategies, dirty checking, the persistence context
- [Spring Security reference](https://docs.spring.io/spring-security/reference/) — filter chains, authentication
- [Redisson docs](https://redisson.org/docs/) — distributed objects and locks

---

# PART 2 — File-by-file reference

This part walks every file in the project, explains what it does, calls out
each annotation/component in it, and describes who calls it and what it calls.

## File tree

```
demo/
├── pom.xml                                         build + dependencies
├── src/main/resources/
│   ├── application.yml                             runtime config
│   └── db/migration/
│       ├── V1__create_users.sql
│       ├── V2__create_assets.sql
│       ├── V3__create_wallets.sql
│       ├── V4__create_transactions.sql
│       ├── V5__create_orders.sql
│       └── V6__create_trades.sql
└── src/main/java/com/tokenizedtradingplatform/demo/
    ├── DemoApplication.java                        bootstrap
    ├── config/
    │   └── SecurityConfig.java
    ├── security/
    │   ├── JwtUtil.java
    │   └── JwtAuthenticationFilter.java
    ├── exception/
    │   ├── InsufficientFundsException.java
    │   ├── ResourceNotFoundException.java
    │   ├── DuplicateResourceException.java
    │   ├── LockAcquisitionException.java
    │   └── GlobalExceptionHandler.java
    ├── domain/
    │   ├── enums/
    │   │   ├── UserRole.java
    │   │   ├── TransactionType.java
    │   │   ├── OrderSide.java
    │   │   ├── OrderStatus.java
    │   │   └── OrderType.java
    │   ├── model/
    │   │   ├── User.java
    │   │   ├── Asset.java
    │   │   ├── Wallet.java
    │   │   ├── Transaction.java
    │   │   ├── Order.java
    │   │   └── Trade.java
    │   └── repository/
    │       ├── UserRepository.java
    │       ├── AssetRepository.java
    │       ├── WalletRepository.java
    │       ├── TransactionRepository.java
    │       ├── OrderRepository.java
    │       └── TradeRepository.java
    ├── application/
    │   ├── service/
    │   │   ├── AuthService.java
    │   │   ├── WalletService.java
    │   │   ├── OrderService.java
    │   │   ├── MatchingEngine.java
    │   │   ├── TradeSettlementService.java
    │   │   ├── TradeExecutionService.java
    │   │   └── PortfolioService.java
    │   └── event/
    │       ├── WalletBalanceChangedEvent.java
    │       ├── OrderRestedEvent.java
    │       ├── WalletEventListener.java
    │       └── OrderBookEventListener.java
    ├── infrastructure/
    │   ├── OrderBookRedisAdapter.java
    │   ├── cache/
    │   │   ├── WalletCacheAdapter.java
    │   │   └── PortfolioCacheAdapter.java
    │   └── lock/
    │       ├── DistributedLockService.java
    │       ├── DistributedLock.java          (annotation)
    │       └── DistributedLockAspect.java
    └── api/
        ├── request/
        │   ├── RegisterRequest.java
        │   ├── LoginRequest.java
        │   ├── DepositRequest.java
        │   ├── WithdrawRequest.java
        │   └── PlaceOrderRequest.java
        ├── response/
        │   ├── AuthResponse.java
        │   ├── WalletResponse.java
        │   ├── TransactionResponse.java
        │   ├── AssetResponse.java
        │   ├── OrderResponse.java
        │   ├── OrderBookResponse.java
        │   ├── TradeResponse.java
        │   └── PortfolioResponse.java
        └── controller/
            ├── AuthController.java
            ├── WalletController.java
            ├── AssetController.java
            ├── OrderController.java
            └── TradeController.java
```

---

## 13. Bootstrap & build

### [pom.xml](demo/pom.xml)
Maven build descriptor. Three things to know:

- **`<parent>`** points to `spring-boot-starter-parent` 3.5.14 — inherits
  versions for hundreds of libraries so we don't have to.
- **`<dependencies>`** — what we pulled in:
  - `spring-boot-starter-web` — Tomcat + Spring MVC + Jackson
  - `spring-boot-starter-data-jpa` — Hibernate + Spring Data JPA
  - `spring-boot-starter-data-redis` — base Redis support (Lettuce client)
  - `spring-boot-starter-validation` — Bean Validation (`@NotNull`, etc.)
  - `spring-boot-starter-security` — security filter chain
  - `spring-boot-starter-actuator` — `/actuator/health`, `/metrics`
  - `postgresql` — JDBC driver
  - `flyway-core` + `flyway-database-postgresql` — schema migrations
  - `redisson-spring-boot-starter` — Redisson client (for distributed locks)
  - `jjwt-api`, `jjwt-impl`, `jjwt-jackson` — JWT library
  - `lombok` — code generation
  - `mapstruct` — added for future use; not used yet
  - `micrometer-registry-prometheus` — exposes metrics in Prometheus format
  - `spring-boot-starter-test`, `testcontainers`, `embedded-redis`, `awaitility` — testing
- **`<build><plugins>`** — Lombok must come *before* MapStruct in the
  annotation processor list, otherwise generated getters won't exist when
  MapStruct runs.

### [application.yml](demo/src/main/resources/application.yml)
Runtime config, three groups:

1. **`spring.datasource`** + **`spring.jpa`** — Postgres URL, HikariCP pool
   settings, Hibernate `ddl-auto: validate`.
2. **`spring.data.redis`** — Redis host/port. Read by both
   `spring-boot-starter-data-redis` and `redisson-spring-boot-starter`.
3. **`spring.flyway`** — Flyway migration location.
4. **`app.*`** — our custom keys (`app.jwt.secret`, `app.cache.wallet-ttl-seconds`,
   etc.). Read with `@Value("${app.jwt.secret}")` in `JwtUtil`.
5. **`management.endpoints`** — exposes Actuator endpoints we want public.

### [DemoApplication.java](demo/src/main/java/com/tokenizedtradingplatform/demo/DemoApplication.java)
Entry point.

- **`@SpringBootApplication`** — composite of `@Configuration`,
  `@EnableAutoConfiguration`, `@ComponentScan` (rooted at this package).
- **`exclude = UserDetailsServiceAutoConfiguration.class`** — disables
  Spring Security's default in-memory user (we authenticate via JWT, no
  form login).
- **`SpringApplication.run`** — boots the entire context.

---

## 14. Database migrations — Flyway

Flyway runs these in order on startup. Once applied, the file's checksum is
recorded in `flyway_schema_history`; never edit an applied migration —
always add the next `V{n+1}__...sql`.

### [V1__create_users.sql](demo/src/main/resources/db/migration/V1__create_users.sql)
`users` table.
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` — Postgres generates UUIDs.
- `email VARCHAR(255) UNIQUE` — login key.
- `password_hash` — bcrypt hash, never plaintext.
- `role VARCHAR(20) DEFAULT 'TRADER'` — matches `UserRole` enum.
- `is_active BOOLEAN` — soft-disable an account.
- `created_at`, `updated_at` — timestamps.
- Index on `email` so login is fast.

### [V2__create_assets.sql](demo/src/main/resources/db/migration/V2__create_assets.sql)
`assets` table + 4 seed rows.
- `id SERIAL` — auto-increment integer.
- `symbol VARCHAR(10) UNIQUE` — `'CASH'`, `'GOLD'`, `'SLVR'`, `'REIT'`.
- `is_cash BOOLEAN` — only one row has this true (`CASH`).
- `is_tradeable BOOLEAN` — `CASH` is `false`; everything else is `true`.
- Seeds: `CASH(id=1)`, `GOLD(id=2)`, `SLVR(id=3)`, `REIT(id=4)`.
  These IDs are referenced by code (`OrderService.CASH_ASSET_ID = 1`).

### [V3__create_wallets.sql](demo/src/main/resources/db/migration/V3__create_wallets.sql)
`wallets` table — one row per (user, asset) pair.
- Two FK columns: `user_id`, `asset_id`.
- `available_balance DECIMAL(28, 8)` — what the user can spend.
- `locked_balance DECIMAL(28, 8)` — held against open orders.
- `version BIGINT` — for Hibernate `@Version` optimistic locking.
- `UNIQUE (user_id, asset_id)` — only one wallet per asset per user.
- `CHECK (available_balance >= 0)` and `CHECK (locked_balance >= 0)` —
  database-level guarantee that no balance can go negative, no matter what
  bug is in our code.

### [V4__create_transactions.sql](demo/src/main/resources/db/migration/V4__create_transactions.sql)
`transactions` ledger — append-only audit log.
- `wallet_id` — the wallet this entry affects.
- `counterparty_wallet_id` — for trades, the other side. Null for deposits.
- `type CHECK IN ('CREDIT','DEBIT')` — direction.
- `balance_after` — snapshot of balance after the entry, for fast statements.
- `reference_id`, `reference_type` — link to the order/trade that caused it.
- Index `(wallet_id, created_at DESC)` powers the transaction history page.

### [V5__create_orders.sql](demo/src/main/resources/db/migration/V5__create_orders.sql)
`orders` table.
- `side`, `type`, `status` — `VARCHAR` with `CHECK` constraints (we picked
  this over Postgres native enums to avoid Hibernate type-cast friction).
- `price DECIMAL(28, 8)`, `quantity DECIMAL(28, 8)`, `filled_quantity`.
- `CHECK (price > 0)`, `CHECK (quantity > 0)`,
  `CHECK (filled_quantity <= quantity)` — invariants enforced at the DB
  level so a buggy service layer can't write an impossible row.
- Composite index `(asset_id, side, status)` — what the matching engine
  will scan in Phase 3.

### [V6__create_trades.sql](demo/src/main/resources/db/migration/V6__create_trades.sql)
`trades` table — immutable record of every fill.
- Five FK columns: `buy_order_id`, `sell_order_id`, `buyer_id`, `seller_id`, `asset_id`.
- `price`, `quantity`, `total_amount` (all `DECIMAL(28, 8)`).
- `CHECK (total_amount = price * quantity)` — DB-enforced consistency so
  no buggy code can write a trade whose total doesn't add up.
- Five indexes covering the common query patterns: by buyer, by seller, by
  asset (market history), by buy order, by sell order.

---

## 15. Domain layer — enums

These are tiny but they shape the type system everywhere.

### [UserRole.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/enums/UserRole.java)
`TRADER`, `ADMIN`. Stored as a string in `users.role`. Mapped via
`@Enumerated(EnumType.STRING)`.

### [TransactionType.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/enums/TransactionType.java)
`CREDIT`, `DEBIT`. The two ways money moves on a wallet ledger.

### [OrderSide.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/enums/OrderSide.java)
`BUY`, `SELL`. Drives the Redis sorted set scoring (`-price` for BUY,
`+price` for SELL) in `OrderBookRedisAdapter`.

### [OrderStatus.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/enums/OrderStatus.java)
`OPEN → PARTIALLY_FILLED → FILLED` (or `CANCELLED` from any non-final state).
The state machine of an order.

### [OrderType.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/enums/OrderType.java)
`LIMIT`, `MARKET`. Today we only place `LIMIT` orders; `MARKET` is reserved.

---

## 16. Domain layer — entities (models)

### [User.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/User.java)
Database row from `users`.
- `@Entity @Table(name = "users")` + Lombok `@Getter @Setter @Builder`.
- `@Id` UUID generated by JPA.
- `email`, `passwordHash` — login fields.
- `role` enum, `active` boolean — both have `@Builder.Default` so the
  builder respects the default value.
- `createdAt`/`updatedAt` — Hibernate auto-fills via `@CreationTimestamp`
  and `@UpdateTimestamp`.

Connections: referenced by `Wallet.user`, `Order.user`. Loaded by
`UserRepository`.

### [Asset.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Asset.java)
Row from `assets`. The four seeded rows are pulled by `AssetController`,
`OrderService`, and looked up by ID/symbol when placing orders.
- `id` is `SERIAL` (Integer, not UUID — assets are a small fixed set).
- `decimalPlaces` — display precision, currently informational only.
- `tradeable`, `cash` — flags. CASH is `cash=true, tradeable=false`.

### [Wallet.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Wallet.java)
The most important entity in the app. It's **not just data** — it has
business logic on it.
- Only `@Getter` (no `@Setter`) — balances can only change through the
  methods below, so external code can never mutate `availableBalance`
  directly.
- `availableBalance` and `lockedBalance` both default to `BigDecimal.ZERO`
  via `@Builder.Default`.
- `@Version Long version` — optimistic-locking column. Hibernate adds
  `WHERE version = ?` to UPDATEs and bumps the value.

Methods (each enforces an invariant):
- `credit(amount)` — adds to available. Throws if amount ≤ 0.
- `debit(amount)` — subtracts from available. Throws
  `InsufficientFundsException` if balance < amount.
- `reserve(amount)` — moves available → locked when an order is placed.
  Calls `debit()` first (so it inherits the insufficient-funds check),
  then increases `lockedBalance`.
- `releaseReservation(amount)` — moves locked → available when an order
  is cancelled.
- `settleDebit(amount)` — removes from locked when a trade settles
  (the money has gone to the counterparty, not back to the user).
- `getTotalBalance()` — convenience sum.

Connections: referenced by `Transaction.wallet`. Loaded by
`WalletRepository` (with a pessimistic-lock variant for writes).

### [Transaction.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Transaction.java)
Append-only ledger row. **No setters** and `@NoArgsConstructor(PROTECTED)`
— once written, immutable.
- `wallet` — owning wallet (FK NOT NULL).
- `counterpartyWallet` — nullable. Deposits/withdrawals leave it null.
- `type CREDIT|DEBIT`, `amount`, `balanceAfter`.
- `referenceId` + `referenceType` — link to the order/trade that produced
  this entry, e.g. `referenceType="DEPOSIT"` or `"TRADE"`.

### [Order.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Order.java)
Row from `orders`.
- `user`, `asset`, `side`, `type`, `status`, `price`, `quantity`,
  `filledQuantity`.
- `@Builder.Default` on `type=LIMIT`, `status=OPEN`, `filledQuantity=ZERO`.
- Methods:
  - `getRemainingQuantity()` = quantity − filledQuantity.
  - `fill(qty)` — bumps filled, transitions status `OPEN`/`PARTIALLY_FILLED`
    or `FILLED` based on whether `filledQuantity == quantity`. Used by
    `TradeSettlementService`.
  - `cancel()` — sets status to `CANCELLED`.

### [Trade.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Trade.java)
Row from `trades`. Like `Transaction`, it's **immutable**: only
`@Getter`, `@NoArgsConstructor(PROTECTED)`, no setters. Once a trade
fires, the row never changes.
- Five `@ManyToOne` relationships: `buyOrder`, `sellOrder`, `buyer`,
  `seller`, `asset` — all `FetchType.LAZY`.
- `price`, `quantity`, `totalAmount` — pre-computed `price × quantity`
  so reporting queries don't need to multiply.
- `@CreationTimestamp Instant executedAt` — Hibernate auto-fills on insert.
- No behaviour methods — a trade is a fact, not a process.

---

## 17. Domain layer — repositories

Spring Data JPA generates the implementation at runtime from the interface.

### [UserRepository.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/UserRepository.java)
- `findByEmail(email)` — derived query. Used by `AuthService.login()`.
- `existsByEmail(email)` — derived. Used by `AuthService.register()` to
  return 409 on duplicates without loading the user.

### [AssetRepository.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/AssetRepository.java)
- `findBySymbol(symbol)` — used by `OrderService.getOrderBook()` to
  validate the symbol exists.
- `findByCashTrue()` — finds the CASH asset (kept for future use).
- `findByTradeableTrue()` — used by `AssetController.listAssets()` to
  return only tradeable assets to the UI.

### [WalletRepository.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/WalletRepository.java)
Most interesting repository — has two pessimistic-lock variants.
- `findByUserIdAndAssetId` — plain read, no lock. For `getWallet()`.
- `findByUserId` (with `JOIN FETCH w.asset`) — eager-loads the asset to
  avoid N+1 in the portfolio listing.
- `findByIdWithLock` — `@Lock(PESSIMISTIC_WRITE)`, `SELECT FOR UPDATE`.
  Reserved for future multi-wallet trade settlement.
- `findByUserIdAndAssetIdWithLock` — same with the user+asset lookup.
  Used everywhere we *write* to a wallet (deposit/withdraw/reserve).

### [TransactionRepository.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/TransactionRepository.java)
- `findByWalletId(walletId, pageable)` — paged history, ordered by
  `createdAt DESC`. Used by `WalletService.getTransactionHistory()`.
- `findByReferenceId(referenceId)` — derived. Useful for looking up a
  transaction by the order/trade that caused it.

### [OrderRepository.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/OrderRepository.java)
- `findByUserIdOrderByCreatedAtDesc` — paged user history.
- `findByUserIdAndStatusIn` — `JOIN FETCH o.asset` for open-orders view
  (passes `[OPEN, PARTIALLY_FILLED]`).
- `findByIdWithLock` — pessimistic for `cancelOrder()` and used by
  `MatchingEngine` when reading an opposing resting order.
- `findOpenOrdersByAssetAndSide` — `JOIN FETCH user, asset` ordered by
  `price ASC, createdAt ASC`. Reserved for future batch matching scans.

### [TradeRepository.java](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/TradeRepository.java)
- `findByUserId(userId, pageable)` — paged trades where the user is
  *either* buyer or seller. `JOIN FETCH asset` to avoid N+1 in the
  response mapper.
- `findByAssetId(assetId, pageable)` — public market history for one
  asset. Same JOIN FETCH pattern.
- `findByOrderId(orderId)` — every fill that touched a given order
  (used to render an order's execution detail). Returns unpaged list
  because one order typically fills against ≤ a few resting orders.

---

## 18. Exception layer

### [InsufficientFundsException.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/InsufficientFundsException.java)
`extends RuntimeException`. Thrown by `Wallet.debit()`. Mapped to **422
Unprocessable Entity** by the global handler.

### [ResourceNotFoundException.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/ResourceNotFoundException.java)
Thrown by services when a user/asset/wallet/order isn't found. Mapped to
**404**.

### [DuplicateResourceException.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/DuplicateResourceException.java)
Thrown by `AuthService.register()` for duplicate email. Mapped to **409**.

### [LockAcquisitionException.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/LockAcquisitionException.java)
Thrown by `DistributedLockService.acquire()` when Redisson `tryLock` returns
false (couldn't acquire within `waitSeconds`). Mapped to **409 Conflict** —
the client should retry. Same status as `DuplicateResourceException` but
semantically different: "the system is busy, try again" vs "this resource
already exists."

### [GlobalExceptionHandler.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/GlobalExceptionHandler.java)
The catch-all.
- **`@RestControllerAdvice`** — applies to all `@RestController` beans.
- One **`@ExceptionHandler`** per exception type, returning a clean
  `ResponseEntity<ErrorResponse>` with the right status code.
- **`MethodArgumentNotValidException`** — thrown when `@Valid` fails.
  The handler walks the binding result and packs a `Map<field, message>`
  into the response details, so the client gets per-field validation
  errors.
- **`Exception.class`** as the last handler — catch-all 500. Hides the
  stack trace from clients (it's still in the server log).
- Inner record **`ErrorResponse(status, message, details, timestamp)`**
  with two convenience constructors so handlers don't have to build the
  full record every time.

---

## 19. Security layer

### [JwtUtil.java](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtUtil.java)
`@Component` — single bean, used by `JwtAuthenticationFilter` and
`AuthService`.
- Constructor takes `@Value("${app.jwt.secret}")` and `app.jwt.expiration-ms`
  from `application.yml`.
- `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` — derives an HMAC key.
  Picks SHA-256/384/512 automatically based on key length.
- `generateToken(userId, email, role)` — uses jjwt 0.12.x fluent API:
  `Jwts.builder().subject(...).claim(...).signWith(secretKey).compact()`.
- `extractClaims(token)` — `Jwts.parser().verifyWith(secretKey).build()
  .parseSignedClaims(token).getPayload()`. Throws if signature/expiry
  fails.
- `extractUserId` — pulls subject and parses to UUID.
- `isTokenValid` — boolean wrapper around parse, swallowing exceptions.

### [JwtAuthenticationFilter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtAuthenticationFilter.java)
`extends OncePerRequestFilter` so it runs exactly once per HTTP request.
- Reads `Authorization: Bearer <token>` header.
- If valid, builds a `UsernamePasswordAuthenticationToken` with:
  - **principal = UUID** (the user id) — that's what
    `@AuthenticationPrincipal UUID userId` in controllers receives.
  - **credentials = null** — already authenticated by JWT.
  - **authorities = `[ROLE_TRADER]`** — Spring Security needs the
    `ROLE_` prefix for `hasRole()` checks.
- Stashes it in `SecurityContextHolder.getContext()`. The context is
  thread-local; downstream code can read it via Spring Security helpers
  or `@AuthenticationPrincipal`.
- If no header / invalid token → context stays empty. Spring Security's
  default authorization rules then reject the request (anonymous user).
- Always calls `filterChain.doFilter(...)` — never short-circuits.

---

## 20. Config layer

### [SecurityConfig.java](demo/src/main/java/com/tokenizedtradingplatform/demo/config/SecurityConfig.java)
`@Configuration @EnableWebSecurity`.

- **`securityFilterChain` `@Bean`** — describes the rules.
  - `csrf().disable()` — stateless API, no browser sessions to protect.
  - `sessionCreationPolicy(STATELESS)` — Spring Security never creates
    `HttpSession`. Every request re-authenticates via JWT.
  - `authorizeHttpRequests`:
    - `/api/v1/auth/**` — `permitAll()` (login/register).
    - `/actuator/health` — `permitAll()` (so K8s liveness probes work).
    - `anyRequest().authenticated()` — everything else needs a valid JWT.
  - `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
    — slots our filter into Spring Security's chain *before* the default
    auth filter.

- **`passwordEncoder` `@Bean`** — `BCryptPasswordEncoder(12)`. The `12`
  is the work factor: 2^12 rounds. Used by `AuthService` to hash and
  compare passwords. Defining it as a bean means Spring Security's
  internals can find it too.

---

## 21. Application layer — services

### [AuthService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/AuthService.java)
Glue between `AuthController` and `UserRepository`/`PasswordEncoder`/`JwtUtil`.
- **`register(req)`**:
  1. `existsByEmail` → throw `DuplicateResourceException` (409) if taken.
  2. `passwordEncoder.encode(...)` — bcrypt the password.
  3. Build a `User` (defaults: role=TRADER, active=true) and `save`.
  4. Generate JWT with the user's id/email/role.
  5. Return `AuthResponse(token, userId, email, role)`.
  Whole method is `@Transactional` so the insert is atomic.
- **`login(req)`**:
  1. `findByEmail` → if missing, `BadCredentialsException` (401).
  2. Reject if `!user.isActive()`.
  3. `passwordEncoder.matches(rawPassword, hash)` — bcrypt compare.
  4. Generate token, return.
  Not `@Transactional` — read-only, no writes.

### [WalletService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/WalletService.java)
Owns wallet lifecycle, reads, deposits/withdrawals, and the public
`recordTransaction` helper for Phase 3.

- **`getOrCreateWallet(userId, assetId)`** — returns existing or creates
  a fresh wallet (zero balances). `@Transactional` so the find+save is
  atomic.
- **`getAllWallets(userId)`** — uses the `JOIN FETCH` query for the
  uncached portfolio listing (`GET /api/v1/wallets`).
- **`getWallet(userId, assetId)`** — **cache-aside** since Phase 5: hits
  `walletCache.get` first, falls through to DB + `walletCache.put` on
  miss. See §10a.
- **`deposit(userId, assetId, req)`**:
  1. `findByUserIdAndAssetIdWithLock` — `SELECT FOR UPDATE` on the row.
     No other transaction can touch this wallet until commit.
  2. `wallet.credit(amount)` — domain method enforces amount > 0.
  3. `walletRepository.save(wallet)` — Hibernate UPDATE.
  4. `recordTransaction(...)` — append a CREDIT row to the ledger.
  5. `eventPublisher.publishEvent(new WalletBalanceChangedEvent(...))`
     — listener invalidates wallet + portfolio cache AFTER_COMMIT.
- **`withdraw(...)`** — same pattern with `wallet.debit(amount)`. The
  `debit` method throws `InsufficientFundsException` (422) if not enough
  funds. Also publishes `WalletBalanceChangedEvent` at the end.
- **`getTransactionHistory(...)`** — returns a `Page<TransactionResponse>`.
  `@Transactional(readOnly = true)`.
- **`recordTransaction(...)`** — left `public` (not `private`) because
  Phase 3's `TradeSettlementService` will call it.
- Two private mappers: `toResponse(Wallet)` and `toTransactionResponse`
  build the DTOs.

### [PortfolioService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/PortfolioService.java)
Cache-aside read service for a user's full portfolio.

- **`getPortfolio(userId)`**:
  1. `portfolioCache.get(userId)` — Redis lookup.
  2. On miss: `walletRepository.findByUserId(userId)` (JOIN FETCH asset)
     → map each `Wallet` to a `WalletResponse` → wrap in `PortfolioResponse`
     with `Instant.now()` snapshot timestamp.
  3. `portfolioCache.put(userId, fresh)`.
  4. Return.

No write methods — invalidation happens via the cache adapter, triggered
by `WalletEventListener` on any `WalletBalanceChangedEvent` for the user.

### [OrderService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/OrderService.java)
Orchestrates order placement, cancellation, and the order book view.

- Constants: `CASH_ASSET_ID = 1`, `ORDER_BOOK_DEPTH = 20`.

- **`placeOrder(userId, req)`**:
  1. Load user + asset; reject if asset not tradeable.
  2. Compute `reserveAmount`: BUY → `price × quantity` cash, SELL →
     `quantity` of the asset.
  3. Reserve asset id: BUY uses CASH (id=1); SELL uses the asset itself.
  4. `findByUserIdAndAssetIdWithLock` on the reserve wallet
     (`SELECT FOR UPDATE`).
  5. Manual balance check (returns `InsufficientFundsException` with the
     numeric details rather than letting `wallet.debit()` throw).
  6. `wallet.reserve(amount)` — moves available → locked.
  7. `orderRepository.saveAndFlush(order)` — write order row, flush so
     `id` and `createdAt` are populated *before* matching runs.
  8. `tradeExecutionService.execute(order)` — runs the matching loop and
     rests any unfilled remainder in Redis. Replaced the old direct
     `orderBook.addOrder()` call in Phase 3.
- **`cancelOrder(userId, orderId)`**:
  1. `findByIdWithLock` on the order.
  2. Reject if it's not yours, or already FILLED/CANCELLED.
  3. Remove from Redis.
  4. Compute `releaseAmount` (proportional to `remainingQuantity`).
  5. Lock the wallet, `releaseReservation(amount)`, save.
  6. Mark order CANCELLED, save.
- **`getUserOrders`** / **`getOpenOrders`** — read views.
- **`getOrderBook(symbol)`**:
  1. Validate symbol exists.
  2. Pull top 20 entries from each side via Redisson `entryRange(0, 19)`.
  3. `aggregateLevels` walks each `ScoredEntry`, looks up the order in
     the DB to get `remainingQuantity`, recovers price from the score,
     and merges entries at the same price into a single
     `OrderBookResponse.Level(price, qty, orderCount)`.

### Why `getOrderBook` does N DB lookups today

We deliberately keep order **price** in the Redis score and **quantity**
in Postgres. A future optimization is to mirror `remainingQuantity` into
the Redis hash so the order book returns purely from Redis — that's the
"hot wallet caching" pattern of Phase 5.

### [TradeSettlementService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/TradeSettlementService.java)
The most critical class in the codebase. **One match = one call to
`settle()`** = one Trade row + one balanced ledger of 4 Transaction rows.

`@Transactional(propagation = Propagation.REQUIRED)` — joins the caller's
transaction (`OrderService.placeOrder` is already `@Transactional`), so
the entire chain of fills for one incoming order rolls back together if
anything fails.

**`settle(buyOrder, sellOrder, tradePrice, qty)` flow:**
1. Compute `totalCost = tradePrice × qty`, `refund = buyerReserved − totalCost`.
   `refund > 0` only when an incoming BUY's limit price exceeded the
   resting SELL price (price improvement).
2. **Ensure all 4 wallets exist** via `ensureWallet(user, asset, id)`.
   Buyer's asset wallet and seller's CASH wallet may not exist yet —
   create them on the fly.
3. **Lock all 4 in UUID-sorted order** via `lockInOrder(ids)`. Sorting
   is the standard deadlock-prevention trick: any two concurrent
   settlements touching overlapping wallets acquire locks in the same
   order, so neither can hold a lock the other needs.
4. **Move money** (5 operations):
   - `buyerCash.settleDebit(totalCost)` — removes from locked; the money
     is "gone" to the seller (never returns to available).
   - If `refund > 0`: `buyerCash.releaseReservation(refund)` — the extra
     amount that was locked at the higher limit price gets returned to
     `available`.
   - `sellerAsset.settleDebit(qty)` — same semantics on the asset side.
   - `buyerAsset.credit(qty)` — buyer receives the asset.
   - `sellerCash.credit(totalCost)` — seller receives cash.
5. Save the `Trade` row.
6. Write **4 Transaction ledger rows** — one per wallet movement, each
   with `referenceId = trade.id` and `referenceType = "TRADE"`. The
   ledger is now a complete audit trail: every trade has exactly 4
   ledger entries.
7. `buyOrder.fill(qty)` and `sellOrder.fill(qty)` — entity methods auto-
   transition status to `PARTIALLY_FILLED` or `FILLED`. Save both.
8. **Publish 4 `WalletBalanceChangedEvent`s** (buyer cash, seller asset,
   buyer asset, seller cash) — the listener invalidates the matching
   wallet + portfolio caches AFTER_COMMIT.

**Why a separate service instead of inline in `MatchingEngine`?** Two
reasons: (1) testability — `MatchingEngine` can be unit-tested with a
mock `TradeSettlementService`. (2) The settlement logic is the high-risk
financial code; isolating it makes it the single audit target.

### [MatchingEngine.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/MatchingEngine.java)
The matching loop. No `@Transactional` of its own — runs inside the
caller's transaction.

**`match(incoming)` loop:**
```
while incoming.remainingQty > 0:
    bestOpposingId = Redis.getBestAsk/Bid(symbol)
    if null → break
    opposing = orderRepository.findByIdWithLock(bestOpposingId)
    if opposing is stale (cancelled/filled/missing):
        Redis.removeOrder(...) and continue
    if prices don't cross → break
    fillQty = min(incoming.remaining, opposing.remaining)
    tradePrice = opposing.price          ← resting order sets price
    settlementService.settle(buy, sell, tradePrice, fillQty)
    if opposing now FILLED:
        Redis.removeOrder(...)
return trades
```

**Key invariants:**
- *Price-time priority*: the resting order's price wins (it was there
  first). The incoming order only pays as good or better than its limit.
- *Stale entries*: a Redis ZSET entry can point to an order that's
  already filled or cancelled (e.g. if a prior matching attempt rolled
  back). The loop heals these by removing them and continuing.
- *Pessimistic lock on opposing*: `findByIdWithLock` blocks any other
  concurrent matcher from settling against the same resting order at
  the same time — single-row safety. Phase 4's Redlock adds a
  per-user lock on top for cross-process safety.

### [TradeExecutionService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/TradeExecutionService.java)
Orchestrator + read-side queries.

- **`execute(incoming)`** (called by `OrderService.placeOrder`):
  1. `matchingEngine.match(incoming)` — fires any trades that can fire.
  2. If incoming still has remaining qty, publish `OrderRestedEvent`.
     The `OrderBookEventListener` adds it to Redis AFTER_COMMIT, so a
     rollback never leaves a phantom resting order in the book.
- **`getUserTrades(userId, pageable)`** — paged history (buyer OR seller).
- **`getAssetTrades(assetId, pageable)`** — paged public market history.
- **`getOrderTrades(userId, orderId)`** — every fill against one specific
  order. Validates ownership (returns 404 if the order doesn't belong
  to this user, indistinguishable from "doesn't exist" — leaks nothing).

---

## 22. Infrastructure layer

### [OrderBookRedisAdapter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/OrderBookRedisAdapter.java)
The Redis Sorted Set wrapper. **The only place** that knows about the
score-encoding scheme.

- **`PRICE_MULTIPLIER = 1_000_000`** — multiplies the price to preserve
  6 decimal places when stored as a `long` in the score.
- **`toScore(side, price)`** — BUY returns `-raw`, SELL returns `+raw`.
  Why negative for BUY: Redis sorted sets order ascending. Negating the
  bid price means the highest bid sorts first when we `ZRANGE 0 N`.
- **Keys**:
  - `orderbook:{symbol}:bids` — `ZSET`, scores negative.
  - `orderbook:{symbol}:asks` — `ZSET`, scores positive.
- **`addOrder` / `removeOrder`** — `ZADD` / `ZREM` on the right key.
- **`getBestBids(symbol, n)` / `getBestAsks(symbol, n)`** — top N entries
  (highest bid / lowest ask) as `Collection<ScoredEntry<String>>`.
- **`getBestBid` / `getBestAsk`** — peek at the very top member.
  Phase 3 matching engine will spin on these.
- **`recoverPrice(side, score)`** — inverse of `toScore`. Negates if BUY,
  divides by 1_000_000 to get back a `BigDecimal`.

Component injection: `RedissonClient` is provided by
`redisson-spring-boot-starter`'s auto-config, which reads
`spring.data.redis.*` from `application.yml`.

### [DistributedLockService.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/lock/DistributedLockService.java)
Thin wrapper around Redisson `RLock`.
- `acquire(key, waitSeconds, leaseSeconds)`:
  - `redisson.getLock(key)` returns an `RLock` reference (no Redis call yet).
  - `lock.tryLock(wait, lease, SECONDS)` blocks up to `wait` seconds; if
    not acquired, returns false → throws `LockAcquisitionException` → 409.
  - On success, returns the `RLock` so the caller can release it later.
- `release(lock)` — safe no-op if `!lock.isHeldByCurrentThread()`. This
  matters if the lease expired mid-operation (the lock auto-released
  itself; calling `unlock()` then would throw).
- Lease auto-expires so a crashed holder doesn't deadlock everything.

### [DistributedLock.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/lock/DistributedLock.java)
**Custom annotation** (not a Spring one). Marker that `DistributedLockAspect`
weaves around.
- `@Target(METHOD)`, `@Retention(RUNTIME)` — applied to methods, available
  at runtime so AOP can read it.
- Fields:
  - `key()` — SpEL **template** (delimiters `#{ ... }`) that resolves to
    the Redis lock key. Method parameter names are accessible as variables:
    `lock:trade:execute:#{#userId}`.
  - `waitSeconds()` default 3 — how long to block trying to acquire.
  - `leaseSeconds()` default 10 — auto-release timer.

### [DistributedLockAspect.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/lock/DistributedLockAspect.java)
The **AOP aspect** that makes `@DistributedLock` work. New concept worth
breaking down:

```java
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class DistributedLockAspect {
    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock distributedLock)
            throws Throwable { ... }
}
```

- **`@Aspect`** (from AspectJ) — marks the class as an AOP aspect.
- **`@Component`** — also a Spring bean so Spring's AOP autoconfig finds it.
- **`@Around("@annotation(distributedLock)")`** — pointcut: "match any
  method annotated with `@DistributedLock`, and bind that annotation to
  the parameter named `distributedLock`."
- **`ProceedingJoinPoint pjp`** — handle to the intercepted method. Call
  `pjp.proceed()` to actually invoke it.
- **`@Order(Ordered.HIGHEST_PRECEDENCE + 100)`** — critical for
  correctness. Spring's `@Transactional` aspect runs at
  `LOWEST_PRECEDENCE`. Lower order number = outer wrapping. We want:
  ```
  acquire lock
    @Transactional begins
      method body
    @Transactional commits
  release lock
  ```
  If we released *before* commit, another thread could acquire the lock
  and read pre-commit state. Order ensures lock outlives the transaction.
- **SpEL key resolution** — `resolveKey(pjp, template)`:
  - Reads parameter names via `DefaultParameterNameDiscoverer` (works
    because Spring Boot compiles with `-parameters`).
  - Stuffs each `(paramName, argValue)` into a `StandardEvaluationContext`
    as `#paramName`.
  - Parses the template with `TemplateParserContext` (delimiters `#{}`),
    evaluates against the context, gets back the resolved string.
- **`try { proceed } finally { release }`** — guaranteed release even
  if the method throws.

### Usage in this project

Applied to two methods in `OrderService`:
```java
@DistributedLock(key = "lock:trade:execute:#{#userId}")
@Transactional
public OrderResponse placeOrder(UUID userId, PlaceOrderRequest req) { ... }

@DistributedLock(key = "lock:trade:execute:#{#userId}")
@Transactional
public OrderResponse cancelOrder(UUID userId, UUID orderId) { ... }
```

This serializes concurrent placements *for the same user*. Two different
users can place orders in parallel — they hold different keys. Within a
user, two concurrent submits queue up; if the queue exceeds `waitSeconds`,
the latecomer gets a 409 and can retry.

### [WalletCacheAdapter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/cache/WalletCacheAdapter.java)
String-bucket cache for `WalletResponse`, keyed by `wallet:balance:{userId}:{assetId}`.
- Constructor injects `RedissonClient`, Spring's autoconfigured `ObjectMapper`,
  `MeterRegistry`, and reads the TTL from `app.cache.wallet-ttl-seconds`.
- Two Micrometer counters built and registered:
  `wallet.cache.hit` and `wallet.cache.miss`.
- `get(userId, assetId)` — fetches the JSON string, deserializes via
  `objectMapper.readValue(...)`. On any exception, **falls through to a
  miss** rather than failing the request. Cache outages must not break reads.
- `put(userId, assetId, value)` — serializes to JSON, writes with TTL
  via `RBucket.set(json, Duration.ofSeconds(ttl))`.
- `invalidate(userId, assetId)` — `delete()` the key.

### [PortfolioCacheAdapter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/cache/PortfolioCacheAdapter.java)
Same shape as `WalletCacheAdapter`, key `portfolio:{userId}`, 60s TTL.
Caches the full `PortfolioResponse` (a record containing a list of
`WalletResponse`).

### Why string buckets instead of Redisson's typed codec

Redisson's `JsonJacksonCodec` enables polymorphic type info globally
(every nested object gets an `@class` field). That fights with records
containing generic lists (PortfolioResponse → List<WalletResponse>) —
empirically the deserializer can't reconstruct the inner elements.
Bypassing Redisson's codec entirely and storing plain JSON strings via
Spring's already-configured ObjectMapper sidesteps the problem and
keeps the values readable via `redis-cli GET <key>`.

### How this composes with the DB pessimistic locks

| Concern | Protected by |
|---|---|
| Same user placing 2 orders racing on their cash wallet | `@DistributedLock` per-user |
| Two threads settling against the same resting order | Pessimistic `findByIdWithLock` on the order |
| Two settlements touching overlapping wallets | UUID-sorted pessimistic `findByIdWithLock` on wallets |
| Two cancels for the same order | Pessimistic `findByIdWithLock` on the order |

Two independent safety layers. Redlock is coarse (per user); DB locks are
fine-grained (per row). The combination prevents every concurrency hazard
we've identified.

---

## 22a. Application events

The `application/event/` package is the bridge between business operations
and side-effects (cache invalidation, Redis order-book updates). Publishers
fire events; listeners consume them after the surrounding transaction
commits.

### [WalletBalanceChangedEvent.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/WalletBalanceChangedEvent.java)
Plain Java record: `(UUID userId, Integer assetId)`. Carries only the
information a listener needs to invalidate the right cache keys — not
the whole wallet object (that could be stale by the time the listener
runs).

### [OrderRestedEvent.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/OrderRestedEvent.java)
Record `(String symbol, OrderSide side, BigDecimal price, UUID orderId)`.
Published from `TradeExecutionService.execute()` when an order has
remaining quantity after matching; the listener writes it to the Redis
sorted set AFTER_COMMIT.

### [WalletEventListener.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/WalletEventListener.java)
Single method:
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onBalanceChanged(WalletBalanceChangedEvent event) {
    walletCache.invalidate(event.userId(), event.assetId());
    portfolioCache.invalidate(event.userId());
}
```
**Why invalidate both caches:** any wallet change affects the portfolio
snapshot too. Invalidating both is correct; updating either would race.

### [OrderBookEventListener.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/OrderBookEventListener.java)
Single method that applies the resting-order ZADD AFTER_COMMIT:
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderRested(OrderRestedEvent event) {
    orderBook.addOrder(event.symbol(), event.side(), event.price(), event.orderId());
}
```

**Why only `OrderRestedEvent` is after-commit (and not `removeOrder`):**
the matching loop in `MatchingEngine` needs Redis to reflect filled
orders mid-iteration to make progress (otherwise the loop sees the same
"best ask" every iteration and never terminates). So mid-matching ZREMs
stay synchronous; only the final "park the remainder" goes through events.

The matching engine's existing stale-entry handling (skip + cleanup
when an opposing order is already FILLED/CANCELLED) reconciles any
drift if a transaction rolls back after we've ZREM'd.

---

## 23. API layer — request DTOs

All request DTOs use **bean validation** annotations. `@Valid` in the
controller triggers them; failures bubble up as
`MethodArgumentNotValidException` → 400 with per-field errors.

### [RegisterRequest.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/request/RegisterRequest.java)
Lombok `@Data` (= getters, setters, toString, equals, hashCode, RequiredArgs ctor).
- `@Email @NotBlank` email
- `@NotBlank @Size(min=8, max=100)` password
- `@NotBlank` fullName

### [LoginRequest.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/request/LoginRequest.java)
- `@Email @NotBlank` email
- `@NotBlank` password (no `@Size` here on purpose — never leak the
  password rules through 400 errors during login)

### [DepositRequest.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/request/DepositRequest.java)
### [WithdrawRequest.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/request/WithdrawRequest.java)
Both have a single `BigDecimal amount` with
`@NotNull @DecimalMin("0.00000001")`. `BigDecimal` rather than `double`
because money math + floats = bugs.

### [PlaceOrderRequest.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/request/PlaceOrderRequest.java)
**Java record** (immutable). Fields:
- `Integer assetId` — `@NotNull`.
- `OrderSide side` — `@NotNull`. Jackson maps the JSON string `"BUY"` /
  `"SELL"` to the enum.
- `BigDecimal price`, `BigDecimal quantity` — both `@NotNull` and
  `@DecimalMin("0.00000001")`.

The other DTOs are classes (because of `@Data`); this one is a record
because we wrote it later and records are simpler for read-only data.

---

## 24. API layer — response DTOs

All return shape only. No business logic. Built by the service layer
right before the controller returns.

### [AuthResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/AuthResponse.java)
`token`, `userId`, `email`, `role`. Returned by login and register.

### [WalletResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/WalletResponse.java)
`id`, `assetSymbol`, `assetName`, `availableBalance`, `lockedBalance`,
`totalBalance`. Note: returns the *symbol/name*, not the asset id —
clients shouldn't care about our internal IDs.

### [TransactionResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/TransactionResponse.java)
`id`, `type`, `amount`, `balanceAfter`, `referenceType`, `createdAt`.

### [AssetResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/AssetResponse.java)
`id`, `symbol`, `name`, `decimalPlaces`, `tradeable`. The asset list view.

### [OrderResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/OrderResponse.java)
**Java record** with a static factory `OrderResponse.from(Order)` —
keeps the mapping next to the DTO instead of repeated in services.
Includes computed `remainingQuantity` so the client doesn't have to
subtract.

### [OrderBookResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/OrderBookResponse.java)
- Outer record: `symbol`, `List<Level> bids`, `List<Level> asks`.
- Nested record: `Level(price, quantity, orderCount)` — aggregated price
  level (multiple orders at the same price get merged).

### [TradeResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/TradeResponse.java)
**Java record** with static `TradeResponse.from(Trade)`.
- Identifies both order sides (`buyOrderId`, `sellOrderId`) and both
  user sides (`buyerId`, `sellerId`).
- Includes computed `totalAmount` and `executedAt` Instant.

### [PortfolioResponse.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/response/PortfolioResponse.java)
**Java record** — `(UUID userId, List<WalletResponse> wallets, Instant snapshotAt)`.
The `snapshotAt` field lets clients see how old the cached value is.
Built by `PortfolioService.getPortfolio` and cached by
`PortfolioCacheAdapter`.

Note: `WalletResponse` (a `@Data` class, not a record) got a
`@NoArgsConstructor` added in Phase 5 because Jackson needs a no-arg
ctor to deserialize cached values back into objects on cache hits.

---

## 25. API layer — controllers

All HTTP entry points. Controllers do **only** three things: receive,
delegate to a service, return. No business logic, no DB access.

### [AuthController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/AuthController.java)
- `POST /api/v1/auth/register` → 201 + `AuthResponse`
- `POST /api/v1/auth/login` → 200 + `AuthResponse`

Both bodies are `@Valid @RequestBody`. Both endpoints are `permitAll()`
in `SecurityConfig`.

### [WalletController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/WalletController.java)
- `GET /api/v1/wallets` — uncached portfolio (DB-direct)
- `GET /api/v1/wallets/portfolio` — **cached** portfolio snapshot via
  `PortfolioService` (Phase 5)
- `GET /api/v1/wallets/{assetId}` — one wallet, cache-aside via
  `WalletCacheAdapter` (Phase 5)
- `POST /api/v1/wallets/{assetId}` — create wallet, returns 201
- `POST /api/v1/wallets/{assetId}/deposit` — body `{amount}`
- `POST /api/v1/wallets/{assetId}/withdraw` — body `{amount}`
- `GET /api/v1/wallets/{assetId}/transactions` — `Pageable` query params
  (`?page=0&size=20`)

### [AssetController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/AssetController.java)
- `GET /api/v1/assets` — list of tradeable assets (excludes CASH).
- `GET /api/v1/assets/{id}` — single asset.

This controller injects `AssetRepository` directly instead of going
through a service. That's a small shortcut: there's no business logic
to add, and creating an `AssetService` just to forward calls would be
ceremony with no payoff.

### [OrderController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/OrderController.java)
- `POST /api/v1/orders` → 201 + `OrderResponse` — places an order.
- `DELETE /api/v1/orders/{orderId}` — cancel.
- `GET /api/v1/orders` — paged history of all your orders.
- `GET /api/v1/orders/open` — only OPEN/PARTIALLY_FILLED.
- `GET /api/v1/orderbook/{symbol}` — public order book view (still
  authenticated; in a real exchange this would be `permitAll`).

The class is mapped at `/api/v1` (not `/api/v1/orders`) because it owns
two URL roots (`/orders` and `/orderbook`).

### [TradeController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/TradeController.java)
Read-only — trades are written via `OrderService.placeOrder` → matching.
- `GET /api/v1/trades` — paged trade history (current user as buyer
  OR seller).
- `GET /api/v1/trades/order/{orderId}` — every fill for one of the
  current user's orders.
- `GET /api/v1/trades/asset/{assetId}` — public market history
  (paged, latest first).

---

## 26. Connection map — who calls what

```
HTTP                                                    External
 │                                                      │
 ▼                                                      │
SecurityFilterChain                                     │
 ├── JwtAuthenticationFilter ──▶ JwtUtil                │
 │                              (validates token)       │
 ▼                                                      │
Controllers                                             │
 ├── AuthController     ──▶ AuthService     ──▶ UserRepository ──▶ Postgres
 │                          ├──▶ PasswordEncoder                 │
 │                          └──▶ JwtUtil                         │
 │                                                               │
 ├── WalletController   ──▶ WalletService   ──▶ WalletRepository      ──▶ Postgres
 │                          ├──▶ AssetRepository                       │
 │                          ├──▶ UserRepository                        │
 │                          └──▶ TransactionRepository                 │
 │                                                                     │
 ├── AssetController    ──▶ AssetRepository ──▶ Postgres               │
 │                                                                     │
 ├── OrderController    ──▶ [DistributedLockAspect]                    │
 │                          ▼                                          │
 │                        OrderService    ──▶ OrderRepository          │
 │                          ├──▶ AssetRepository                       │
 │                          ├──▶ UserRepository                        │
 │                          ├──▶ WalletRepository                      │
 │                          └──▶ TradeExecutionService                 │
 │                                  ├──▶ MatchingEngine                │
 │                                  │     ├──▶ OrderRepository         │
 │                                  │     ├──▶ OrderBookRedisAdapter   │
 │                                  │     └──▶ TradeSettlementService  │
 │                                  │           ├──▶ WalletRepository  │
 │                                  │           ├──▶ AssetRepository   │
 │                                  │           ├──▶ TradeRepository   │
 │                                  │           ├──▶ TransactionRepo   │
 │                                  │           └──▶ OrderRepository   │
 │                                  └──▶ OrderBookRedisAdapter ──▶ Redis
 │
 └── TradeController    ──▶ TradeExecutionService (read-only methods)
                            └──▶ TradeRepository                       │
                                                                       │
GlobalExceptionHandler  ◀── all controllers throw       ◀── all services throw

Side-effect bus (Phase 5):
  WalletService / TradeSettlementService / OrderService / TradeExecutionService
     │  publishEvent(...)
     ▼
  ApplicationEventPublisher
     │  AFTER_COMMIT
     ▼
  WalletEventListener      ──▶ WalletCacheAdapter.invalidate(...)
                           └─▶ PortfolioCacheAdapter.invalidate(...)
  OrderBookEventListener   ──▶ OrderBookRedisAdapter.addOrder(...)
```

**The four golden rules this layout enforces**

1. Controllers never touch the DB or Redis — only services do.
2. Repositories never call services — calls go top-down only.
3. The domain layer (`domain/model`, `domain/enums`) imports nothing
   from `api/`, `application/`, or `infrastructure/`. It's the bottom
   of the dependency graph.
4. Cross-cutting concerns (security, exception mapping, Redisson
   wiring) live in `config/`, `security/`, `exception/`,
   `infrastructure/` — not mixed into business code.

---

## 27. Reading order recommendation

If you want to read the codebase end-to-end, follow this order:

1. **[application.yml](demo/src/main/resources/application.yml)** — what
   the app is configured to do.
2. **[V1–V5 migrations](demo/src/main/resources/db/migration/)** — the
   data model.
3. **[domain/enums/](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/enums/)** —
   vocabulary.
4. **[domain/model/](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/)** —
   entities, especially `Wallet` (it has all the business invariants).
5. **[domain/repository/](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/)** —
   data access surface.
6. **[exception/](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/)** —
   error model.
7. **[security/](demo/src/main/java/com/tokenizedtradingplatform/demo/security/)** +
   **[config/SecurityConfig.java](demo/src/main/java/com/tokenizedtradingplatform/demo/config/SecurityConfig.java)** —
   how requests are authenticated.
8. **[application/service/](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/)** —
   workflows. Read in this order:
   - `AuthService` (simplest)
   - `WalletService` (introduces pessimistic locking + ledger)
   - `OrderService` (introduces Redis ordering)
   - `TradeSettlementService` (the financial heart — read this slowly)
   - `MatchingEngine` (uses settlement)
   - `TradeExecutionService` (uses matching)
9. **[infrastructure/OrderBookRedisAdapter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/OrderBookRedisAdapter.java)** —
   Redis-specific order-book code.
10. **[infrastructure/lock/](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/lock/)** —
    AOP-driven distributed lock subsystem.
11. **[infrastructure/cache/](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/cache/)** —
    Wallet and portfolio cache adapters with Micrometer metrics.
12. **[application/event/](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/)** —
    event records and `@TransactionalEventListener(AFTER_COMMIT)` listeners.
13. **[api/](demo/src/main/java/com/tokenizedtradingplatform/demo/api/)** —
    DTOs and controllers. These are last because they're trivial once
    the services make sense.
