# Annotations Reference — Every Decorator in This Project

Java calls them "annotations," not "decorators" (decorators are a Python/JS
concept). They're metadata attached to classes/methods/fields that frameworks
read at compile or runtime to do work for you.

This guide lists **every annotation used in this codebase**, grouped by what
it does. For each:
- What it means
- Where it's used here (with file links)
- What the alternatives are
- Why we picked this one

---

## Table of contents

1. [Spring stereotype annotations](#1-spring-stereotype-annotations)
2. [Spring web / MVC annotations](#2-spring-web--mvc-annotations)
3. [Configuration / dependency injection](#3-configuration--dependency-injection)
4. [Spring Data JPA — repositories](#4-spring-data-jpa--repositories)
5. [JPA / Hibernate — entity mapping](#5-jpa--hibernate--entity-mapping)
6. [Transactions](#6-transactions)
7. [Bean validation](#7-bean-validation)
8. [Spring Security](#8-spring-security)
9. [Lombok — code generation](#9-lombok--code-generation)
10. [Exception handling](#10-exception-handling)
11. [AOP — aspect-oriented programming](#11-aop--aspect-oriented-programming)

---

## 1. Spring stereotype annotations

These mark a class as a **bean** (a singleton object Spring will manage).

### `@SpringBootApplication`
*Used in:* [DemoApplication.java](demo/src/main/java/com/tokenizedtradingplatform/demo/DemoApplication.java)

A composite of three annotations:
- `@SpringBootConfiguration` (a special `@Configuration`)
- `@EnableAutoConfiguration` — turns on auto-config for everything on the classpath
- `@ComponentScan` — scans this package and below for `@Component`/`@Service`/`@Repository`/`@Controller`

```java
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class DemoApplication { ... }
```

The `exclude` argument turns off Spring Security's default in-memory user
because we authenticate via JWT.

**Alternative:** Write the three annotations separately. Done in legacy
codebases; nobody does this anymore. Keep `@SpringBootApplication`.

### `@Component`
*Used implicitly* via `@RestController`, `@Service`, `@Configuration`, and
explicitly on:
- [JwtUtil.java](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtUtil.java)
- [JwtAuthenticationFilter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtAuthenticationFilter.java)
- [OrderBookRedisAdapter.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/OrderBookRedisAdapter.java)

The generic stereotype: "this class is a bean."

**Alternatives & when to pick which:**
| Annotation | Semantic meaning |
|---|---|
| `@Component` | Generic — use when nothing more specific fits |
| `@Service` | Business logic / orchestration layer |
| `@Repository` | Data access layer — also enables exception translation (JDBC `SQLException` → Spring `DataAccessException`) |
| `@Controller` / `@RestController` | Web layer |
| `@Configuration` | Class defines `@Bean`-producing methods |

Functionally identical except `@Repository` adds exception translation. We
use `@Component` for adapters and utilities (`JwtUtil`,
`OrderBookRedisAdapter`) because they're not really services or data access.

### `@Service`
*Used in:* [AuthService](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/AuthService.java),
[WalletService](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/WalletService.java),
[OrderService](demo/src/main/java/com/tokenizedtradingplatform/demo/application/service/OrderService.java)

Marks orchestration / business logic classes. Identical to `@Component`
runtime-wise; the difference is documentation: anyone reading the codebase
knows this is the layer that makes decisions.

**Alternative:** `@Component`. Don't — using `@Service` makes the layer
explicit when grepping.

### `@Repository`
*Used implicitly.* We don't write `@Repository` ourselves — Spring Data JPA
adds it automatically to interfaces extending `JpaRepository`. The benefit
(exception translation) is wired up for free.

### `@Configuration`
*Used in:* [SecurityConfig.java](demo/src/main/java/com/tokenizedtradingplatform/demo/config/SecurityConfig.java)

Marks a class that produces beans via `@Bean` methods. Spring proxies the
class so calling `someBean()` from another `@Bean` method returns the same
singleton (not a new instance).

**Alternative:** XML config. It's 2026 — never write Spring XML.

### `@RestController`
*Used in:* all 4 controllers in
[api/controller/](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/)

Composite of `@Controller` + `@ResponseBody`. The `@ResponseBody` part means
"don't render a view template, serialize the return value to JSON" (via
Jackson by default).

**Alternative:** `@Controller` + `@ResponseBody` on every method. Old
pattern from before `@RestController` existed in Spring 4.0. Don't use it.

### `@RestControllerAdvice`
*Used in:* [GlobalExceptionHandler.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/GlobalExceptionHandler.java)

Composite of `@ControllerAdvice` + `@ResponseBody`. Tells Spring "this class
contains exception handlers that apply to all `@RestController` beans."

**Alternative:**
- `@ExceptionHandler` directly on each controller — duplicates code, easy
  to miss a controller.
- `@ControllerAdvice` (no `@ResponseBody`) — handlers would have to wrap
  responses themselves; our `ResponseEntity<ErrorResponse>` returns get
  serialized fine either way, but `@RestControllerAdvice` is the
  conventional choice for REST APIs.

---

## 2. Spring web / MVC annotations

### `@RequestMapping`
*Used in:* every controller, e.g. [WalletController.java](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/WalletController.java)

```java
@RequestMapping("/api/v1/wallets")
```

Sets the URL prefix for all methods in the class.

### `@GetMapping`, `@PostMapping`, `@DeleteMapping`
*Used in:* every controller method.

Shortcuts for `@RequestMapping(method = ...)`. They exist for
`@PutMapping` and `@PatchMapping` too — we just don't have any update
endpoints yet.

**Alternative:** `@RequestMapping(method = RequestMethod.GET, value = ...)`.
Verbose, no benefit. Always prefer the shortcuts.

### `@PathVariable`
*Used in:* every controller with a templated URL, e.g.
[WalletController.java#L36](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/WalletController.java#L36)

```java
@GetMapping("/{assetId}")
public ResponseEntity<...> getWallet(@PathVariable Integer assetId)
```

Extracts a value from the URL path.

**Alternative:** `@RequestParam` (query string) — semantically different.
Use path variables for resource identifiers, query params for filters and
options.

### `@RequestBody`
*Used in:* `register`, `login`, `deposit`, `withdraw`, `placeOrder`.

Tells Spring to deserialize the HTTP body (JSON via Jackson) into the
parameter object.

**Alternative:** `@ModelAttribute` — binds form fields from
`application/x-www-form-urlencoded`. We're a JSON API, so always
`@RequestBody`.

### `@Valid`
*Used in:* every controller method that takes a request DTO.

Triggers bean validation on the parameter. If validation fails, throws
`MethodArgumentNotValidException`, which `GlobalExceptionHandler` catches
and turns into a 400 with per-field errors.

**Alternative:** `@Validated` (from Spring, not Jakarta). The two differ
in advanced cases:
- `@Validated` supports **validation groups** (apply different rules in
  different contexts).
- `@Validated` works on class-level for method-level validation.
- `@Valid` is the standard JSR-380 annotation; works on method parameters
  and for nested validation.

We use `@Valid` because we don't need groups and it's the JSR standard.

### `@AuthenticationPrincipal`
*Used in:* every authenticated controller method that needs the user, e.g.
[WalletController.java#L29](demo/src/main/java/com/tokenizedtradingplatform/demo/api/controller/WalletController.java#L29)

```java
public ResponseEntity<...> getAllWallets(@AuthenticationPrincipal UUID userId)
```

Spring Security pulls the principal out of `SecurityContextHolder` and
binds it to this parameter. Our `JwtAuthenticationFilter` sets the principal
to the user's `UUID`, so `userId` arrives directly typed.

**Alternatives:**
- `SecurityContextHolder.getContext().getAuthentication().getPrincipal()` —
  works but ugly, not testable, leaks framework details into business code.
- `Principal principal` parameter — only gives you `getName()`, no rich
  type.
- `@CurrentUser` custom annotation wrapping the same lookup — over-engineering
  for a small project.

`@AuthenticationPrincipal` is the cleanest because the type is whatever
your filter put in. We put a UUID; we get a UUID.

---

## 3. Configuration / dependency injection

### `@Bean`
*Used in:* [SecurityConfig.java](demo/src/main/java/com/tokenizedtradingplatform/demo/config/SecurityConfig.java)

Marks a method inside a `@Configuration` class as producing a bean. Spring
calls it once and caches the return value.

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}
```

**Alternative:** Annotate the *class* with `@Component`. That works only
when you own the class. `BCryptPasswordEncoder` is from Spring Security, so
we can't add `@Component` to it — `@Bean` in our config is the only way.

### `@Value`
*Used in:* [JwtUtil.java#L20-L21](demo/src/main/java/com/tokenizedtradingplatform/demo/security/JwtUtil.java#L20-L21)

```java
public JwtUtil(@Value("${app.jwt.secret}") String secret, ...) {
```

Injects a property from `application.yml`. Supports SpEL
(`@Value("#{...}")`), defaults (`@Value("${foo:default}")`), system env
fallback.

**Alternatives:**
- `@ConfigurationProperties("app.jwt")` — binds an entire group of
  properties to a typed object. Cleaner when you have 5+ related properties.
  Worth it for a real "auth config" object; we only had two values and
  they were used in one place, so `@Value` was simpler.
- `Environment.getProperty(...)` injected manually — verbose, no compile-time
  link to the property name.

### `@Autowired`
**Not used in this project.** We use Lombok's `@RequiredArgsConstructor`
for constructor injection instead.

Why: With one constructor on a Spring bean, `@Autowired` is implicit and
the framework wires the dependencies automatically. `@RequiredArgsConstructor`
generates the constructor; Spring picks it up. Result: zero `@Autowired`
clutter, all dependencies are `final`, easy to test by passing mocks to
the constructor.

**Old patterns we're avoiding:**
- Field injection: `@Autowired private FooService foo;` — can't be `final`,
  hides dependencies, hostile to testing without reflection.
- Setter injection: `@Autowired public void setFoo(...)` — same problems
  plus mutable state.

---

## 4. Spring Data JPA — repositories

### `@Query`
*Used in:* [WalletRepository](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/WalletRepository.java),
[OrderRepository](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/OrderRepository.java),
[TransactionRepository](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/TransactionRepository.java)

Custom JPQL when derived methods can't express the query.

```java
@Query("SELECT w FROM Wallet w JOIN FETCH w.asset WHERE w.user.id = :userId")
List<Wallet> findByUserId(@Param("userId") UUID userId);
```

**Alternatives:**
- **Derived method names** — `findByUserIdAndAssetId` is auto-parsed by
  Spring Data into JPQL. We use this for simple lookups
  (`UserRepository.findByEmail`).
- **Native SQL** — `@Query(value = "SELECT ...", nativeQuery = true)`.
  We'd use this if we needed Postgres-specific SQL features. For now,
  JPQL is portable and sufficient.
- **Specifications / Criteria API** — type-safe but verbose. Worth it for
  dynamic queries with optional filters. None of ours need that.
- **JOOQ / QueryDSL** — fluent DSLs with full compile-time safety.
  Heavier dependency. Overkill for this project.

We pick `@Query` whenever derived methods get unwieldy or we need
`JOIN FETCH` to avoid N+1.

### `@Param`
*Used alongside every `@Query` with named parameters.*

```java
@Query("... WHERE w.user.id = :userId")
Optional<Wallet> findByIdWithLock(@Param("userId") UUID userId);
```

**Alternative:** Positional parameters (`?1`, `?2`). Brittle — reorder the
parameters and your queries break silently. Always prefer named.

### `@Lock`
*Used in:* [WalletRepository](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/WalletRepository.java),
[OrderRepository](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/repository/OrderRepository.java)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.id = :id")
Optional<Wallet> findByIdWithLock(@Param("id") UUID id);
```

Adds `FOR UPDATE` to the SELECT, blocking other transactions until ours
commits.

**Alternatives:**
- `LockModeType.PESSIMISTIC_READ` — `FOR SHARE`. Allows concurrent reads
  with locks; only blocks writes. Useful but rarely the right choice for
  money operations.
- `LockModeType.OPTIMISTIC` / `OPTIMISTIC_FORCE_INCREMENT` — lock-free,
  uses the `@Version` column to detect conflicts. Requires retry logic
  from the caller.
- No lock + `@Version` — same as above without `@Lock`.

We use `PESSIMISTIC_WRITE` for wallets because:
1. Writes are short and frequent — retry storms under contention are bad.
2. Wallets are the bottleneck for *the same user*; cross-user contention
   is rare. Locking a single row briefly is fine.
3. We still keep `@Version` on `Wallet` as a backstop in case a code path
   forgets to use the locked finder.

---

## 5. JPA / Hibernate — entity mapping

### `@Entity`
*Used on:* User, Asset, Wallet, Transaction, Order.

Marks a class as a database row.

**Alternative:** `@Embeddable` — for value objects embedded in another
entity (no own table). We don't have any.

### `@Table(name = "...")`
*Used on:* every entity.

Sets the table name. Without it, Hibernate guesses from the class name.
We always set it explicitly because:
1. Refactoring the class shouldn't accidentally rename the table.
2. The table name is user-controlled (e.g. `orders`, not `order` which is
   a SQL reserved word).

### `@Id`
The primary key. JPA requires exactly one (or `@IdClass`/`@EmbeddedId` for
composite keys, which we don't use).

### `@GeneratedValue(strategy = ...)`
| Strategy | What it does | Where we use it |
|---|---|---|
| `UUID` | JPA generates a UUID before insert | `User`, `Wallet`, `Transaction`, `Order` |
| `IDENTITY` | Uses DB auto-increment (`SERIAL`) | `Asset` |
| `SEQUENCE` | Uses a DB sequence object | not used |
| `AUTO` | Hibernate picks one | not used |
| `TABLE` | Stores generation state in a separate table | obsolete |

UUIDs for everything user-related: clients can generate them, distributable,
no info leak from incremental IDs. `IDENTITY` for `assets` because the
table is small, fixed, and integers are easier to reference in code
(`CASH_ASSET_ID = 1`).

### `@Column`
Tunes the column mapping.

```java
@Column(name = "password_hash", nullable = false)
@Column(nullable = false, precision = 28, scale = 8)
@Column(unique = true, length = 10)
```

**Alternative:** Don't annotate. Hibernate guesses column name from field
name and uses default constraints. We annotate explicitly for monetary
fields (`precision`, `scale`) and snake_case mapping (`name = "..."`).

### `@ManyToOne(fetch = FetchType.LAZY)` + `@JoinColumn`
*Used on:* every FK relationship in the entities.

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;
```

**`FetchType` alternatives:**
- `EAGER` — load related entity in the same query. *Default for `@ManyToOne`.*
  Disastrous for performance: every Order loaded triggers a User load,
  every User load triggers more loads, etc. (the **N+1 problem**).
- `LAZY` — load on first access. We always use this.

To eager-load when you actually need it, use `JOIN FETCH` in JPQL
(see `WalletRepository.findByUserId`).

**Other relationship types you'll see in JPA:**
- `@OneToMany` — the inverse side. We don't use it because we always
  navigate from child to parent.
- `@ManyToMany` — needs a join table. We don't have any.
- `@OneToOne` — when modeling shared PKs.

### `@Enumerated(EnumType.STRING)`
*Used on:* `User.role`, `Order.side/type/status`, `Transaction.type`.

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private UserRole role;
```

**Alternatives:**
- `EnumType.ORDINAL` (the default!) — stores the enum's index (0, 1, 2…).
  **Never use it.** Insert a new enum value in the middle and existing
  rows silently corrupt.
- Custom `@Converter` — for old-school string codes (`"M"`, `"F"`).
  Unnecessary when you can name your enum values directly.

`STRING` always.

### `@Version`
*Used on:* [Wallet.java#L41](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Wallet.java#L41)

```java
@Version
@Column(nullable = false)
private Long version;
```

Hibernate adds `WHERE version = ?` to UPDATEs and increments the column.
Two transactions updating the same row → one of them gets
`OptimisticLockException`.

**Alternative:** No `@Version`. We keep it on `Wallet` as a safety net
even though we lock pessimistically — defense in depth.

### `@CreationTimestamp` / `@UpdateTimestamp`
*Used on:* most entities.

Hibernate-specific (not JPA standard). Auto-fills timestamps on insert /
on update.

**Alternatives:**
- `@PrePersist` / `@PreUpdate` lifecycle methods — boilerplate.
- DB-level `DEFAULT NOW()` and triggers — works, but the entity field
  stays null until you re-fetch.
- Auditing via `@CreatedDate` + `@LastModifiedDate` (Spring Data JPA's
  auditing) — gives you `@CreatedBy` / `@LastModifiedBy` too. Worth it
  if you need to track *who* changed a row. Overkill here.

---

## 6. Transactions

### `@Transactional`
*Used in:* every service method that writes data.

```java
@Transactional
public WalletResponse deposit(...) { ... }

@Transactional(readOnly = true)
public List<WalletResponse> getAllWallets(...) { ... }
```

Spring wraps the bean in a proxy: opens a DB transaction before the
method, commits after, rolls back on `RuntimeException`.

**Common parameters:**
- `readOnly = true` — hint to skip dirty checking, faster reads.
- `propagation = ...` — controls behaviour when called within an existing
  transaction. Default `REQUIRED` is almost always right.
  - `REQUIRED` (default) — join existing transaction or start a new one.
    We name it explicitly on `TradeSettlementService.settle()` for
    documentation: "I expect to run inside the caller's transaction."
  - `REQUIRES_NEW` — suspend outer, start a new one. Use when the inner
    operation must commit independently (e.g. audit logging).
  - `NESTED` — savepoint within outer. Rare.
- `rollbackFor = Exception.class` — rollback on checked exceptions too
  (default is RuntimeException only).
- `isolation = ...` — DB isolation level. Defaults to whatever the DB has
  configured.
- `timeout = N` — kill the transaction after N seconds.

**The `Propagation.REQUIRED` story in Phase 3:**
`OrderService.placeOrder` is `@Transactional`. It calls
`tradeExecutionService.execute()` which is also `@Transactional`, which
calls `matchingEngine.match()` (no annotation — joins ambient transaction),
which calls `tradeSettlementService.settle()` (`@Transactional(REQUIRED)`).
All of these participate in *one* DB transaction. If settlement fails on
the 3rd of 4 fills, every reservation, fill, ledger row, and order
update for the entire order rolls back. That's the property we want.

**Alternative:** Manual `transactionTemplate.execute(...)`. Verbose and
error-prone. `@Transactional` wins unless you need very fine control.

**Self-invocation gotcha:** `@Transactional` only works through the proxy.
Calling another `@Transactional` method via `this.foo()` bypasses it.
Always cross a Spring bean boundary.

### `@EventListener`
*Used in:* not used directly — every listener in this project uses the
transactional variant below.

Spring's plain event-bus listener:
```java
@EventListener
public void onSomething(SomethingEvent e) { ... }
```
Fires synchronously the moment `applicationEventPublisher.publishEvent(...)`
is called. Same thread, same call stack.

**Why we don't use it:** if the publishing code is `@Transactional` and
the listener runs *immediately*, you've done side effects (cache delete,
Redis write, email send) that should NOT have happened if the transaction
later rolls back.

### `@TransactionalEventListener(phase = AFTER_COMMIT)`
*Used in:*
[WalletEventListener.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/WalletEventListener.java),
[OrderBookEventListener.java](demo/src/main/java/com/tokenizedtradingplatform/demo/application/event/OrderBookEventListener.java)

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onBalanceChanged(WalletBalanceChangedEvent event) {
    walletCache.invalidate(event.userId(), event.assetId());
}
```

Defers the listener until the surrounding transaction reaches a specific
phase:

| Phase | When it fires |
|---|---|
| `BEFORE_COMMIT` | Right before commit — listener can throw to abort. |
| `AFTER_COMMIT` (default) | After the transaction has successfully committed. |
| `AFTER_ROLLBACK` | After the transaction rolled back. |
| `AFTER_COMPLETION` | After the transaction ends (commit or rollback). |

Use this for any side effect that must only happen on a successful commit:
cache invalidation, downstream API calls, notifications.

**Gotcha:** if `publishEvent` is called *outside* a transaction, the
listener never fires by default. Set
`@TransactionalEventListener(fallbackExecution = true)` to fire anyway.

**Alternatives:**
- `@EventListener` — fires immediately. Wrong for transactional side effects.
- Inline call to the side effect — couples invalidation logic into every
  caller; easy to forget. Listener pattern keeps side effects in one place.
- Message broker (Kafka, RabbitMQ) — overkill for in-process events.
  Reach for it when listeners need to outlive the process.

### `ApplicationEventPublisher` (not an annotation, but the partner API)

Spring auto-provides this bean. Inject via constructor:
```java
@Service
@RequiredArgsConstructor
public class WalletService {
    private final ApplicationEventPublisher eventPublisher;

    public void deposit(...) {
        // ...write to DB...
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(userId, assetId));
    }
}
```
The event object can be any class; records are ideal — immutable, value
semantics, zero boilerplate.

---

## 7. Bean validation

JSR-380 (Jakarta Bean Validation). All these come from
`jakarta.validation.constraints`. Triggered by `@Valid` in controllers.

| Annotation | Where used | Constraint |
|---|---|---|
| `@NotNull` | `DepositRequest.amount`, `PlaceOrderRequest.*` | not null (allows empty string) |
| `@NotBlank` | `RegisterRequest.email`, `password`, `fullName` | not null AND not whitespace-only |
| `@NotEmpty` | not used here | not null AND length > 0 |
| `@Email` | `RegisterRequest.email`, `LoginRequest.email` | RFC 5321 email |
| `@Size(min=, max=)` | `RegisterRequest.password` (8–100) | string/collection length |
| `@DecimalMin("0.00000001")` | `Deposit/Withdraw/PlaceOrderRequest.amount/price/quantity` | numeric ≥ N |
| `@DecimalMax` | not used | numeric ≤ N |
| `@Min`/`@Max` | not used | for integral types |
| `@Pattern` | not used | regex |
| `@Positive`/`@Negative` | not used | sign check |
| `@Past`/`@Future` | not used | date constraints |

**Why `@NotBlank` over `@NotNull` for strings:** `@NotNull` would let an
empty string through. `@NotBlank` rejects `""` and `"   "` too.

**Why `@DecimalMin("0.00000001")` instead of `@Positive`:** `@Positive`
would accept `0.00000000000001` and any tiny positive number. Our DB schema
uses `DECIMAL(28, 8)`, so anything finer than 8 decimal places truncates
to zero. `@DecimalMin` with the correct minimum tracks the schema.

---

## 8. Spring Security

### `@EnableWebSecurity`
*Used in:* [SecurityConfig.java](demo/src/main/java/com/tokenizedtradingplatform/demo/config/SecurityConfig.java)

Activates Spring Security's web filter chain. Required if you want to
customize via a `SecurityFilterChain` bean.

**Alternative:** None — without this, Spring Security's auto-config still
runs but you can't override it.

### `@EnableMethodSecurity` *(not used yet)*
Would let you write `@PreAuthorize("hasRole('ADMIN')")` on individual
methods. We don't have admin endpoints yet so we haven't enabled it.

---

## 9. Lombok — code generation

Lombok is an annotation processor. The annotations get expanded into real
Java code at compile time.

### `@Getter` / `@Setter`
*Used on:* most entities.

Generates accessors for every (non-static) field. Works at the field level
too — `@Getter` on a single field gives you only that one accessor.

**On `Wallet`** we only have `@Getter`, no `@Setter` — balances must be
mutated only through the domain methods (`credit`, `debit`, `reserve`).
This is a deliberate choice to enforce the invariant.

### `@NoArgsConstructor`
*Used on:* every entity.

Generates `public Foo() {}`. JPA requires this for reflection-based proxy
creation.

**`@NoArgsConstructor(access = AccessLevel.PROTECTED)`** on
[Transaction.java#L15](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Transaction.java#L15)
and [Order.java#L19](demo/src/main/java/com/tokenizedtradingplatform/demo/domain/model/Order.java#L19)
makes it visible only to JPA proxies, not external callers — forces use
of the builder.

`AccessLevel` options: `PUBLIC`, `PROTECTED`, `PACKAGE`, `PRIVATE`, `MODULE`,
`NONE`. We use `PROTECTED` because Hibernate proxies subclass our entity.
`PRIVATE` would break Hibernate.

### `@AllArgsConstructor`
*Used on:* every entity, every response DTO.

Generates a constructor with every field. Required for `@Builder` to work
on classes (the builder calls this constructor under the hood).

### `@RequiredArgsConstructor`
*Used on:* every service, every controller, every adapter.

Generates a constructor with only `final` and `@NonNull` fields. Combined
with `private final` fields, this is our **constructor-injection** pattern:

```java
@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    // Spring auto-wires both via the generated constructor
}
```

**Alternatives:**
- Hand-written constructor — boring boilerplate.
- Field injection with `@Autowired` — see Section 3.
- Setter injection — same problems as field injection.

### `@Builder`
*Used on:* every entity (User, Asset, Wallet, Transaction, Order).

Generates a fluent builder:
```java
User.builder().email("a@b.com").fullName("Alice").build();
```

**Alternative:** Constructor with positional args. Annoying when you have
6+ fields and many are optional.

### `@Builder.Default`
*Used on:* every default-valued field in entities.

```java
@Builder.Default
private boolean active = true;
```

Without it, `@Builder` ignores the field initializer and the builder leaves
it null/false. **Always pair `@Builder` with `@Builder.Default` for fields
that have defaults.**

### `@Data`
*Used on:* request DTOs (`RegisterRequest`, `LoginRequest`,
`DepositRequest`, `WithdrawRequest`) and response DTOs (`AuthResponse`,
`WalletResponse`, `TransactionResponse`, `AssetResponse`).

Composite of:
- `@Getter`
- `@Setter`
- `@ToString`
- `@EqualsAndHashCode`
- `@RequiredArgsConstructor`

**Why we don't use `@Data` on entities:** `@EqualsAndHashCode` on entities
is a footgun. The default uses every field, including lazy-loaded
relationships, which can trigger DB hits during equality checks. Custom
ID-only `equals`/`hashCode` is the right call for entities.

For DTOs, `@Data` is fine — they're throwaway carriers.

### Java records — alternative to Lombok DTOs
*Used in:* `PlaceOrderRequest`, `OrderResponse`, `OrderBookResponse`.

A Java 16+ language feature: `record Foo(int x, String y) {}` generates
constructor, accessors, `equals`, `hashCode`, `toString`. Final fields,
immutable.

**Records vs `@Data`:**
| | record | `@Data` class |
|---|---|---|
| Mutable | no | yes |
| Inheritance | can't extend | can |
| Bean validation on fields | yes | yes |
| Lombok-free | yes | no |
| Empty constructor for frameworks | no | yes |

We use records for newer DTOs because they're simpler. Older DTOs predate
the records-everywhere decision.

---

## 10. Exception handling

### `@ExceptionHandler`
*Used in:* every method of [GlobalExceptionHandler.java](demo/src/main/java/com/tokenizedtradingplatform/demo/exception/GlobalExceptionHandler.java)

```java
@ExceptionHandler(InsufficientFundsException.class)
public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
    return ResponseEntity.status(422).body(...);
}
```

Catches the specified exception (and subclasses) thrown anywhere in the
controller layer.

**Alternatives:**
- Try/catch in every controller — boilerplate, easy to miss.
- Servlet filter that catches `Exception` — too low-level, can't easily
  return JSON, can't access controller context.
- Spring's `ResponseStatusException` — `throw new ResponseStatusException(404, "...")`.
  Quick and dirty but couples your business logic to HTTP. We separate
  concerns: services throw `ResourceNotFoundException`, the handler maps
  it to 404.
- `ProblemDetail` (RFC 9457) — Spring 6.0+ supports this. Worth migrating
  to one day for standardization. We use a custom `ErrorResponse` because
  we wanted full control over the shape.

### `@ControllerAdvice` *(used implicitly via `@RestControllerAdvice`)*
Same as `@RestControllerAdvice` but without `@ResponseBody`. Pick
`@RestControllerAdvice` for REST APIs.

---

## 11. AOP — aspect-oriented programming

These come from AspectJ (`org.aspectj.lang.annotation.*`) and Spring
(`org.springframework.core.annotation.Order`). Triggered by adding
`spring-boot-starter-aop` to `pom.xml`.

### `@Aspect`
*Used in:* [DistributedLockAspect.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/lock/DistributedLockAspect.java)

Marks a class as an AOP aspect. The class still needs `@Component` (or
some other stereotype) for Spring to register it as a bean.

**Alternative:** XML aspect declarations. Don't.

### `@Around`
*Used in:* `DistributedLockAspect.around(...)`

The advice type: code runs *around* the matched method. You get a
`ProceedingJoinPoint` parameter and choose when (or whether) to call
`pjp.proceed()`. Return value is whatever you want the caller to see.

```java
@Around("@annotation(distributedLock)")
public Object around(ProceedingJoinPoint pjp, DistributedLock distributedLock)
        throws Throwable { ... }
```

The string argument is a **pointcut expression** in AspectJ syntax. Here
`@annotation(distributedLock)` means "match methods annotated with the
annotation type bound to the parameter named `distributedLock`."

**Other advice types we could have used:**
- `@Before("...")` — runs before. Can't decide to skip the method.
- `@After("...")` — runs after, regardless of return/throw.
- `@AfterReturning("...")` — runs after successful return only.
- `@AfterThrowing("...")` — runs only when method throws.

We picked `@Around` because the lock must be released in a `finally`
block — we need to handle both success and exception paths in one place.

### `@Order` (Spring)
*Used in:* `DistributedLockAspect` with `Ordered.HIGHEST_PRECEDENCE + 100`.

Controls the order in which multiple aspects wrap the same method.
**Lower number = outer wrapping** (counterintuitive but consistent with
"highest precedence"). Defaults to `LOWEST_PRECEDENCE` = lowest priority
= innermost.

In this project: Spring's `@Transactional` aspect runs at
`LOWEST_PRECEDENCE`. Our `DistributedLockAspect` runs at
`HIGHEST_PRECEDENCE + 100`. So order of execution:

```
[DistributedLock]  acquire
  [Transactional]  begin
    [method]       run
  [Transactional]  commit
[DistributedLock]  release
```

Lock is held for the entire transaction, including commit.

**Alternative:** `@Priority` (JSR-250). Spring honours `@Order` more
consistently, especially for aspects. Stick with `@Order`.

### Custom annotation: `@DistributedLock`
*Defined in:* [DistributedLock.java](demo/src/main/java/com/tokenizedtradingplatform/demo/infrastructure/lock/DistributedLock.java)
*Applied in:* `OrderService.placeOrder`, `OrderService.cancelOrder`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();
    long waitSeconds() default 3;
    long leaseSeconds() default 10;
}
```

- **`@Target(METHOD)`** — can only be placed on methods.
- **`@Retention(RUNTIME)`** — visible at runtime so AOP can read it.
  Without this, the annotation is stripped from the bytecode.
- The fields are parameters the aspect reads via reflection.

**Why a custom annotation instead of just calling `lockService.acquire()`
directly in the method body?** Two reasons:
1. **Separation of concerns** — `placeOrder` shouldn't know it's protected
   by a lock. That's a deployment/infrastructure decision.
2. **Composability** — adding `@DistributedLock` to a new method is one
   line. Wrapping with `try/finally` and SpEL key resolution everywhere
   is fragile and easy to forget.

**SpEL templates in the key** — the aspect uses
`TemplateParserContext` with delimiters `#{ ... }`. Inside the template,
method parameter names are variables prefixed with `#`:

```java
@DistributedLock(key = "lock:trade:execute:#{#userId}")
```

For this to work, parameter names must survive compilation. Spring Boot
3 compiles with `-parameters` by default — without that flag, you'd see
arg names like `arg0`, `arg1` and SpEL would fail.

---

## Quick reference — annotations used per file

| File | Notable annotations |
|---|---|
| `DemoApplication.java` | `@SpringBootApplication(exclude=...)` |
| `SecurityConfig.java` | `@Configuration`, `@EnableWebSecurity`, `@RequiredArgsConstructor`, `@Bean` |
| `JwtUtil.java` | `@Component`, `@Value` |
| `JwtAuthenticationFilter.java` | `@Component`, `@RequiredArgsConstructor` |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice`, `@ExceptionHandler` |
| Entities (User/Asset/Wallet/Transaction/Order/Trade) | `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@ManyToOne`, `@JoinColumn`, `@Enumerated`, `@Version`, `@CreationTimestamp`, `@UpdateTimestamp`, `@Getter`, (`@Setter`), `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`, `@Builder.Default`, `AccessLevel.PROTECTED` |
| Repositories (incl. `TradeRepository`) | `@Query`, `@Param`, `@Lock` |
| Services | `@Service`, `@RequiredArgsConstructor`, `@Transactional` (with `readOnly`, `propagation`) |
| `TradeSettlementService.java` | `@Service`, `@RequiredArgsConstructor`, `@Transactional(propagation = Propagation.REQUIRED)` |
| `MatchingEngine.java` | `@Service`, `@RequiredArgsConstructor` (no `@Transactional` — runs in caller's transaction) |
| `TradeExecutionService.java` | `@Service`, `@RequiredArgsConstructor`, `@Transactional` with `Propagation.REQUIRED` on write methods and `readOnly = true` on read methods |
| `OrderBookRedisAdapter.java` | `@Component`, `@RequiredArgsConstructor` |
| `DistributedLockService.java` | `@Service`, `@RequiredArgsConstructor`, Lombok `@Slf4j` |
| `DistributedLock.java` | `@Target`, `@Retention` (custom annotation definition) |
| `DistributedLockAspect.java` | `@Aspect`, `@Component`, `@Order`, `@Around`, `@RequiredArgsConstructor` |
| `OrderService.java` (Phase 4 update) | `@DistributedLock(key = "lock:trade:execute:#{#userId}")` on `placeOrder` and `cancelOrder` |
| `WalletCacheAdapter.java` / `PortfolioCacheAdapter.java` | `@Component`, `@Slf4j`, `@Value` (TTL) |
| `PortfolioService.java` | `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)` |
| `WalletBalanceChangedEvent.java` / `OrderRestedEvent.java` | (records — no class-level annotations) |
| `WalletEventListener.java` / `OrderBookEventListener.java` | `@Component`, `@RequiredArgsConstructor`, `@Slf4j`, `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| Request DTOs | `@Data`, `@NotNull`, `@NotBlank`, `@Email`, `@Size`, `@DecimalMin` (records use compact `@NotNull`/`@DecimalMin` directly) |
| Response DTOs (incl. `TradeResponse`) | `@Data`, `@AllArgsConstructor` (records: none beyond Java syntax) |
| Controllers (incl. `TradeController`) | `@RestController`, `@RequestMapping`, `@GetMapping`/`@PostMapping`/`@DeleteMapping`, `@PathVariable`, `@RequestBody`, `@Valid`, `@AuthenticationPrincipal`, `@RequiredArgsConstructor` |

---

## Cheat sheet — "which annotation should I use when..."

| Need | Annotation |
|---|---|
| Class is a Spring bean (generic) | `@Component` |
| Class holds business logic | `@Service` |
| Class produces other beans | `@Configuration` + `@Bean` methods |
| Class handles HTTP | `@RestController` |
| Method handles GET request | `@GetMapping` |
| Inject a value from `application.yml` | `@Value` (or `@ConfigurationProperties` for groups) |
| Inject another bean | `private final` field + `@RequiredArgsConstructor` |
| Run inside a DB transaction | `@Transactional` |
| Read-only DB query | `@Transactional(readOnly = true)` |
| Must join caller's transaction (no independent commit) | `@Transactional(propagation = Propagation.REQUIRED)` |
| Lock a row for update | `@Lock(LockModeType.PESSIMISTIC_WRITE)` |
| Custom JPA query | `@Query` |
| Validate request body | `@Valid` on parameter, validation annotations on fields |
| Map enum as string in DB | `@Enumerated(EnumType.STRING)` |
| Generate getters/setters/etc | `@Data` (DTO), `@Getter` (entity) |
| Constructor with all `final` deps | `@RequiredArgsConstructor` |
| Builder pattern | `@Builder` (and `@Builder.Default` per defaulted field) |
| Catch exception globally | `@ExceptionHandler` in `@RestControllerAdvice` class |
| Get the current user | `@AuthenticationPrincipal UUID userId` |
| Cross-cutting behaviour around methods (lock, log, retry…) | `@Aspect` class with `@Around`, applied via custom annotation |
| Define a custom annotation | `@interface Foo` + `@Target` + `@Retention(RUNTIME)` |
| Control multi-aspect ordering | `@Order(Ordered.HIGHEST_PRECEDENCE + N)` (lower = outer) |
| Publish an event | inject `ApplicationEventPublisher`, call `publishEvent(...)` |
| Listen for an event after the transaction commits | `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| Listen for an event regardless of transaction | `@EventListener` (use sparingly — see notes) |
