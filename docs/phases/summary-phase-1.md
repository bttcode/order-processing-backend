# Phase 1 — Implementation Engineering Log & Phase 2 Preparation

## What Phase 1 Delivers

One synchronous vertical slice: `POST /orders` and `GET /orders/{id}`, backed by
real PostgreSQL, Flyway migrations, and a domain model that enforces its own
invariants. No concurrency, no caching, no reactive pipeline.

The constraint that shaped every decision: **the architecture must be correct
from day one**. Phase 2 adds concurrency on top of this structure without
rewiring it. Phase 4 extracts hexagonal boundaries that already exist implicitly
here. Getting the layers wrong now means a rewrite later, not a refactor.

---

## Dependency Rule — The One Rule That Cannot Break

```
Infrastructure  →  Application  →  Domain
     ↓                  ↓              ↓
Spring, JPA,      Port interfaces,   Pure Java
REST, Flyway      @Transactional,    OrderStatus,
                  Commands           Money, Order
```

Domain imports nothing outside `java.*`. If you find a Spring import inside
`domain/`, the direction is inverted and Phase 4's gate test — domain compiles
with no Spring jars on the classpath — will fail.

Application imports domain types plus `org.springframework.transaction` only.
The transaction API is the single permitted Spring dependency at this layer.

---

## Schema Decisions (ADR-001) — Why They Matter for Performance

The schema is not just storage. At 10k orders/sec the schema IS the performance
architecture. Every decision here has a Phase 3 consequence.

### Dual-key pattern on `orders` and `products`

```sql
-- orders
pk   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY  -- internal, never leaves infra
id   UUID NOT NULL UNIQUE                              -- UUIDv7, public API handle

-- products
pk   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
id   VARCHAR(50) NOT NULL UNIQUE                       -- business key e.g. PROD-001
```

**Why BIGINT PK instead of UUID PK:**
UUIDv4 as a clustered PK causes a B-tree page split on nearly every INSERT
because new keys land at random leaf positions. At 10k inserts/sec on a table
that has grown past L3 cache, every INSERT evicts a cached page to read a random
one. BIGINT is sequential — inserts are always appended to the rightmost leaf,
zero page splits, maximum cache locality.

UUIDv7 is time-ordered (monotonically increasing within a millisecond) so the
UNIQUE index on `orders.id` also avoids fragmentation. We get the external-facing
randomness of UUID with internal sequential performance.

**Why `order_items` uses `product_pk BIGINT` not `product_id VARCHAR(50)` as FK:**
The FK join `order_items → products` fires on every inventory reservation. BIGINT
comparison is a single 64-bit integer operation. VARCHAR(50) is a byte-by-byte
scan up to 50 bytes. The FK index entries in `order_items` are 8 bytes vs 50
bytes — 6× smaller, 6× more entries per index page, 6× better cache utilization.

**`product_id VARCHAR(50)` is kept as a denormalized snapshot**, not a FK. It
records what the product business key was at order creation time, independent of
future product changes. Historical accuracy without join cost.

### Indexes designed for the query patterns, not the table shape

```sql
-- Covers GET /orders?customerId=X&status=Y ORDER BY created_at DESC
CREATE INDEX idx_order_composite ON orders(customer_id, status, created_at DESC);

-- Partial index: active orders only — terminal states never queried by status
CREATE INDEX idx_order_status ON orders(status)
    WHERE status NOT IN ('CONFIRMED', 'CANCELLED');
```

The composite index column order matters: `customer_id` first because it is
the highest-cardinality filter (equality), `status` second (equality), `created_at`
last (range/sort). Reversing any two columns makes the index unusable for this
query. Verified in Phase 3 with `EXPLAIN ANALYZE` — must show `Index Scan`, not
`Seq Scan`.

### `@Version` columns are live from day one

Both `orders.version` and `products.version` are in the schema now. They are not
used in Phase 1 (no concurrent writes) but wiring optimistic locking into the
schema at migration V1 means Phase 2 adds behavior, not schema changes. Any schema
change after data exists is a table rewrite.

---

## Transaction Boundary — Where `@Transactional` Lives and Why

### The mistake that was caught

Initial implementation put `@Transactional` on `OrderService.createOrder()`:

```java
// WRONG — Spring annotation in domain layer
public class OrderService {
    @Transactional   // org.springframework.transaction — domain is now Spring-aware
    public Order createOrder(...) { ... }
}
```

This violates the domain purity rule. Spring's `@Transactional` on a concrete
class also requires CGLIB subclass proxying, which means Spring must be able to
subclass `OrderService` — coupling the domain to Spring's proxy mechanism.

### The correct placement

`@Transactional` lives on the **port interface** in the application layer:

```java
// application/port/in/CreateOrderUseCase.java
public interface CreateOrderUseCase {
    @Transactional           // legal — application layer owns transaction demarcation
    Order createOrder(CreateOrderCommand command);
}

public interface QueryOrderUseCase {
    @Transactional(readOnly = true)   // Hibernate skips dirty checking — ~15% faster reads
    Optional<Order> findById(UUID orderId);
}
```

`UseCaseConfiguration` returns the interface type. Spring wraps it in a JDK
dynamic proxy (not CGLIB — the proxy target is an interface) that intercepts
calls and applies the transaction declared on the interface method. `OrderService`
has zero Spring imports. The domain rule holds.

**Why `readOnly = true` on query methods matters:** Hibernate flushes the session
before every query to ensure the query sees the current state. With `readOnly =
true`, Hibernate skips the flush entirely because writes are not expected. For a
system doing 10k reads/sec this is measurable.

---

## The `OrderJpaAdapter.save()` Bug — INSERT vs UPDATE

### What was broken

The original mapper always constructed a new `OrderEntity`:

```java
public Order save(Order order) {
    OrderEntity entity = mapper.toEntity(order, products); // new object, pk = null
    return mapper.toDomain(jpaRepo.save(entity));          // pk null → INSERT, always
}
```

Spring Data's `SimpleJpaRepository.save()` checks `entity.pk == null`. If null,
it calls `entityManager.persist()` (INSERT). If not null, it calls
`entityManager.merge()` (UPDATE).

Since `pk` is `GENERATED ALWAYS AS IDENTITY` with `insertable = false`, the
field is always null on a freshly constructed `OrderEntity`. Every call to
`save()` attempted an INSERT. The second call — updating status from
`PENDING_PAYMENT` to `CONFIRMED` — hit a unique constraint violation on
`orders.id` (the UUIDv7 column).

### The fix

```java
public Order save(Order order) {
    List<ProductEntity> products = resolveProductEntities(order);

    OrderEntity entity = (order.getId() == null)
        ? mapper.toNewEntity(order, products)
        : jpaRepo.findByPublicId(order.getId())
              .map(existing -> mapper.updateEntity(existing, order, products))
              .orElseGet(() -> mapper.toNewEntity(order, products));
    // orElseGet: first save() call — row doesn't exist yet, fall through to INSERT

    return mapper.toDomain(jpaRepo.save(entity));
}
```

The mapper was split into two methods with explicit contracts:

- `toNewEntity()` — called only on first INSERT, `pk` is null, `@PrePersist` fires
- `updateEntity(existing, order, products)` — mutates the loaded managed entity
  in place; Hibernate tracks the dirty fields and issues a targeted UPDATE

The critical detail in `updateEntity`: it calls `existing.getItems().clear()` then
`addAll()`. This only works because `@OneToMany` is declared with
`orphanRemoval = true`. Without it, `clear()` detaches the old `OrderItemEntity`
objects from the collection but leaves the rows in the database. With it,
Hibernate DELETEs the orphaned rows automatically before the INSERT of new ones.

### Why the extra SELECT per save is acceptable now

Every `save()` that is an UPDATE now does a `findByPublicId` SELECT before the
UPDATE — one extra indexed read. In Phase 1 this is negligible. In Phase 3,
profiling under load may show this as a hot path. The fix at that point is to
keep the `OrderEntity` in the Hibernate session for the duration of the
`createOrder()` transaction instead of re-fetching it — pass it through the
pipeline. That is a Phase 3 optimization guided by data, not a Phase 1 concern.

---

## Lazy Loading — Why It Was Left, What Was Fixed

### The situation

`OrderEntity.items` is `FetchType.LAZY`. `findByPublicId` does not JOIN FETCH
items. `toDomain()` iterates items. If the session is closed when `toDomain()`
runs, Hibernate throws `LazyInitializationException`.

### Why it was not optimized away

`07-delivery.md § LO-4` explicitly lists this as a Phase 3 deliverable:

> Identify and fix at least one N+1 query (order items eager vs lazy loading)

The N+1 problem on list queries (`GET /orders?customerId=X` returning 20 orders,
each triggering a separate item SELECT) is the real problem to profile and
document. That cannot be demonstrated until Phase 2 adds the list endpoint.
Fixing lazy loading prematurely removes the learning checkpoint.

### What was fixed — session boundary safety

The lazy load works in Phase 1 because all call sites are inside an active
transaction. That assumption is fragile. One future method calling `findById`
outside a transaction will throw at runtime with no compile-time warning.

`findByPublicId` was updated to JOIN FETCH for the single-row case:

```java
// JpaOrderRepository.java
@Query("SELECT o FROM OrderEntity o LEFT JOIN FETCH o.items WHERE o.id = :publicId")
Optional<OrderEntity> findByPublicId(@Param("publicId") UUID publicId);
```

This eliminates the hidden lazy load for `GET /orders/{id}` permanently — one
SQL with a JOIN instead of two SELECTs. No session dependency. No
`LazyInitializationException` possible regardless of how future callers use it.

JOIN FETCH on a single-row result has no Cartesian product risk. The risk only
appears on list queries (one JOIN per parent × N children = N² rows), which is
exactly the Phase 3 problem to profile and document.

List queries (`findByCustomerId` etc.) are left lazy — added in Phase 2, profiled
in Phase 3.

---

## Timezone Bug — JVM Timezone Leaking into PostgreSQL

### What happened

`docker-compose up` starts PostgreSQL 15. On startup, HikariCP opens the
connection pool and sends the JVM's default timezone to PostgreSQL via the JDBC
`TimeZone` parameter. The OS timezone was `Asia/Saigon`. PostgreSQL 15's
timezone database uses `Asia/Ho_Chi_Minh` — `Asia/Saigon` is not a recognized
identifier. Connection fails. Flyway never runs.

### The fix — three defense layers, all applied

**Layer 1 — JVM default, before Spring starts:**
```java
public class OrderProcessingApplication {
    static {
        // Fires before any Spring bean is instantiated, before HikariCP opens
        // any connection. This is the earliest possible interception point.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
```

**Layer 2 — HikariCP connection init:**
```yaml
spring:
  datasource:
    hikari:
      connection-init-sql: "SET TIME ZONE 'UTC'"
```
Runs on every new connection after it is opened — catches any connection that
bypasses the JVM default for any reason.

**Layer 3 — Hibernate JDBC timezone:**
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
```
Ensures Hibernate uses UTC when binding `Timestamp` parameters, regardless of
the session timezone.

Any one of these three would have fixed the immediate error. All three together
ensure UTC consistency across the full stack: JVM → HikariCP → PostgreSQL session
→ Hibernate bind parameters → stored `TIMESTAMP` values.

---

## The Order Pipeline — Exact Execution Sequence

```
POST /api/v1/orders
│
├─ Bean Validation (@Valid) → 400 Bad Request on failure
│
├─ Controller: CreateOrderRequest → CreateOrderCommand
│   No domain objects in the HTTP layer. Command is an immutable record
│   defined inside CreateOrderUseCase — it cannot be constructed with
│   invalid field combinations.
│
└─ OrderService.createOrder() [inside @Transactional]
    │
    ├─ resolveItems(): productRepository.findById() per item
    │   → 422 UnprocessableEntity on unknown product ID
    │   Phase 1: sequential. Phase 2: CompletableFuture.allOf() in parallel.
    │
    ├─ hasStock() per product → throws InsufficientInventoryException
    │   → order.transitionTo(INSUFFICIENT_INVENTORY)
    │   → orderRepository.save() [INSERT — first and only save for failed orders]
    │   → 422 returned
    │
    ├─ addItem() per item — validates quantity > 0 in OrderItem constructor
    │   Illegal state is impossible to construct, not caught at runtime.
    │
    ├─ Inventory deduction: product.setInventoryQuantity(qty - requested)
    │   productRepository.save() per product [UPDATE, loads existing entity]
    │   Phase 1: sequential, no locking. Phase 2: @Version retry.
    │
    ├─ order.transitionTo(PENDING_PAYMENT) — state machine enforced by canTransitionTo()
    │   orderRepository.save() [INSERT — first persist of the order]
    │
    ├─ paymentGateway.authorize() — MockPaymentGateway, always succeeds in Phase 1
    │   On failure path: transitionTo(PAYMENT_FAILED) → restoreInventory() → save()
    │
    ├─ order.transitionTo(PAYMENT_AUTHORIZED)
    │   order.confirm(transactionId)
    │     → transitionTo(CONFIRMED)
    │     → sets confirmedAt = Instant.now()
    │     → sets estimatedDeliveryDate = now + 3 business days (skip Sat/Sun)
    │
    └─ orderRepository.save() [UPDATE — loads existing entity, mutates status fields]
        → mapper.toDomain(savedEntity) → Order returned to controller
        → 201 Created + Location header
```

On any exception inside the transaction boundary: `@Transactional` rolls back
all writes atomically. No partial state is possible — inventory deductions,
order inserts, status updates all go together or not at all.

---

## Phase 1 Shortcuts — Risks and Exact Fix Points

| Shortcut | Exact risk | Fix location in Phase 2 |
|---|---|---|
| Sequential inventory check | Two threads both pass `hasStock()` on the same product, both deduct — inventory goes negative | Extract to `InventoryService`, wrap each product check in `CompletableFuture.supplyAsync(inventoryCheckExecutor)` |
| No `OptimisticLockException` retry | Under contention, first loser throws, order fails rather than retrying with fresh data | Resilience4j `Retry` on `InventoryService.reserveInventory()`, max 3 attempts, exponential backoff |
| Inventory restore on payment failure is inline | If `restoreInventory()` throws, deducted stock is gone — BR-6 violated | Phase 2: extract to async compensating action; Phase 4: proper saga with event-driven rollback |
| Two `OrderService` instances in `UseCaseConfiguration` | Harmless now — both are stateless. Breaks if state is added to `OrderService` | Phase 4: split into `CreateOrderUseCaseImpl` and `QueryOrderUseCaseImpl`, each a proper `@Service` |
| `MockPaymentGateway` never fails | Cannot exercise `PAYMENT_FAILED` path, retry logic, or BR-6 inventory release | Phase 3: add `payment.mock.failure-rate` config property, return failure when `Math.random() < rate` |
| No idempotency key handling | Duplicate `POST /orders` with same payload creates two orders, charges twice | Phase 5: `idempotency_keys` table lookup before use-case execution |
| No correlation ID in logs | Cannot trace a single request across log lines under concurrent load | Phase 3: `RequestContext` request-scoped bean, populated by `HandlerInterceptor`, injected into MDC |

---

## Structural Constraints That Must Never Change

These are not preferences — violating any of them breaks a future phase gate.

**1. `OrderService` has zero Spring imports.**
The Phase 4 gate test compiles `domain/` with no Spring jars on the classpath.
One `@Autowired` or `@Transactional` import in `OrderService` fails the compile.
`@Transactional` stays on the port interface in `application/port/in/`.

**2. Always load existing `ProductEntity` before `productRepository.save()`.**
If `ProductJpaAdapter.save()` constructs a new `ProductEntity` instead of loading
the existing one, Hibernate sees a detached entity without a `pk`. `jpaRepo.save()`
calls `persist()` (INSERT), the sequence advances, a duplicate row is inserted
(or a constraint violation fires), and `@Version` resets to 0 — optimistic locking
silently stops working for that product.

**3. `order_items.product_pk` (BIGINT) is the FK, not `product_id` (VARCHAR).**
Any JPQL joining `order_items` to `products` must traverse the entity relationship
(`orderItem.product`) not the snapshot string (`orderItem.productId`). The snapshot
exists for historical accuracy — the product name or business key at order time,
preserved even if the product is later deleted. Using it as a join key is semantically
wrong and bypasses the BIGINT FK performance advantage.

**4. `V1__initial_schema.sql` is immutable.**
Flyway checksums every migration file. Editing `V1__initial_schema.sql` after it
has been applied makes Flyway refuse to start on every environment that has already
run it. All schema changes from Phase 2 onward go in `V2__*.sql`, `V3__*.sql`, etc.

**5. `orders.pk` and `products.pk` never cross the infrastructure boundary.**
No DTO, domain model, or application-layer class may reference the `pk` field.
Add an ArchUnit rule in Phase 4:
```java
@ArchTest
static final ArchRule pk_must_not_leave_persistence_layer =
    noFields().that().haveName("pk")
        .should().beDeclaredInClassesThat()
            .resideOutsideOfPackage("..infrastructure.persistence..")
        .because("surrogate pk is an infrastructure concern");
```

---

## What Phase 2 Needs — Concrete Entry Points

### Dependencies to add to `pom.xml` now

```xml
<!-- Resilience4j — retry for inventory, circuit breaker for payment in Phase 4 -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>

<!-- WebFlux — reactive pipeline runs inside MVC app in hybrid mode -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Adding these now avoids a `pom.xml` change mid-Phase 2 that invalidates already-running
load tests.

### `InventoryService` — promotion from stub to real class

The stub in `domain/service/InventoryService.java` becomes:

```java
// domain/service/InventoryService.java — pure Java, no Spring
public class InventoryService {

    private final ProductRepository productRepository;

    // Called by application-layer wrapper that owns @Transactional(REPEATABLE_READ)
    // and Resilience4j retry — this class knows nothing about either
    public void reserveInventory(String productId, int quantity) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        if (!product.hasStock(quantity)) {
            throw new InsufficientInventoryException(productId, quantity);
        }
        product.setInventoryQuantity(product.getInventoryQuantity() - quantity);
        productRepository.save(product);
        // OptimisticLockException propagates up to the retry wrapper in app layer
    }
}
```

The application-layer wrapper that adds `@Transactional(isolation = REPEATABLE_READ)`
and Resilience4j retry lives in `application/usecase/` or `application/config/`.
`InventoryService` stays annotation-free.

### `ExecutorConfiguration` — new file in Phase 2

```java
// infrastructure/config/ExecutorConfiguration.java
@Configuration
public class ExecutorConfiguration {

    @Bean("orderProcessingExecutor")
    public ThreadPoolExecutor orderProcessingExecutor() {
        return new ThreadPoolExecutor(
            8, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
            // CallerRunsPolicy: when queue is full, the HTTP thread executes
            // the task itself — blocks it from accepting new requests.
            // This IS the backpressure mechanism. Not a workaround.
        );
    }

    @Bean("inventoryCheckExecutor")
    public ThreadPoolExecutor inventoryCheckExecutor() {
        return new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new ThreadPoolExecutor.AbortPolicy()
            // AbortPolicy: throw RejectedExecutionException immediately.
            // The inventory check caller handles it — fail the order fast
            // rather than letting it queue indefinitely.
        );
    }
}
```

### Parallel inventory check — extraction point

`resolveItems()` in `OrderService` is already isolated as a private method.
Phase 2 pulls it into `InventoryService.checkAll()`:

```java
// The shape Phase 2 will build toward:
public List<ReservedItem> checkAll(List<OrderItemCommand> items) {
    List<CompletableFuture<ReservedItem>> futures = items.stream()
        .map(cmd -> CompletableFuture.supplyAsync(
            () -> checkSingle(cmd), inventoryCheckExecutor))
        .toList();

    try {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        futures.forEach(f -> f.cancel(true));
        throw new InventoryCheckTimeoutException();
    }

    // All-or-nothing: if any future completed exceptionally, release all reservations
    boolean anyFailed = futures.stream().anyMatch(CompletableFuture::isCompletedExceptionally);
    if (anyFailed) releaseAll(futures);
    return futures.stream().map(CompletableFuture::join).toList();
}
```

### `ReentrantReadWriteLock` — where it goes

The lock applies to an in-memory inventory snapshot used to avoid hitting
PostgreSQL on every read under high concurrency. This is separate from
optimistic locking (which is the DB-level correctness guarantee):

```java
// Part of InventoryService in Phase 2
private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
private final Map<String, Integer> snapshot = new ConcurrentHashMap<>();

public int getStock(String productId) {
    lock.readLock().lock();
    try { return snapshot.getOrDefault(productId, 0); }
    finally { lock.readLock().unlock(); }
}

public void updateSnapshot(String productId, int newQuantity) {
    lock.writeLock().lock();
    try { snapshot.put(productId, newQuantity); }
    finally { lock.writeLock().unlock(); }
}
```

Read lock: multiple threads can read concurrently — no blocking between readers.
Write lock: exclusive — all readers block while a write is in progress.
The snapshot is populated by `DataSeeder` on startup and updated after each
confirmed reservation.

### Reactive pipeline — hybrid mode setup

`OrderController` stays as Spring MVC. The pipeline inside runs reactive:

```java
// Phase 2 target shape inside the use case:
public Mono<Order> processOrder(CreateOrderCommand command) {
    return Mono.fromCallable(() -> validateAndBuildOrder(command))
        .flatMap(order -> checkInventoryReactive(order))   // Mono wrapping CF
        .flatMap(order -> authorizePaymentReactive(order)) // 5s timeout
        .flatMap(order -> Mono.fromCallable(() -> confirmOrder(order)))
        .doOnError(InsufficientInventoryException.class, e -> releaseInventory(command))
        .doOnError(PaymentFailedException.class, e -> releaseInventory(command))
        .subscribeOn(Schedulers.fromExecutor(orderProcessingExecutor));
}
```

The controller blocks with `.block()` initially — this is hybrid mode. The reactive
pipeline gains non-blocking I/O benefits on the internal steps even while the HTTP
layer remains servlet-based. Switching the controller to reactive (returning
`Mono<ResponseEntity<OrderResponse>>`) is a Phase 3 decision, after profiling shows
thread contention is the bottleneck.

### TEST-4 — the Phase 2 exit gate

This test must pass 10 consecutive times in CI before Phase 3 starts:

```java
@Test
void concurrentOrders_mustNotOversell() throws Exception {
    // 50 threads × 5 units = 250 total demand, 100 stock
    int threads = 50, qty = 5, stock = 100;
    Product product = createProduct("PROD-1", stock);

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch startGate = new CountDownLatch(threads); // synchronized start
    List<Future<Boolean>> futures = new ArrayList<>();

    for (int i = 0; i < threads; i++) {
        futures.add(executor.submit(() -> {
            startGate.countDown();
            startGate.await(); // all threads released simultaneously — max contention
            try {
                orderService.createOrder(createRequest("PROD-1", qty));
                return true;
            } catch (Exception e) {
                return false;
            }
        }));
    }

    long successes = futures.stream().map(this::get).filter(Boolean::booleanValue).count();

    // Hard invariant — if this fails, inventory went negative: oversell confirmed
    Product final_ = productRepository.findById("PROD-1").orElseThrow();
    assertThat(final_.getInventoryQuantity()).isGreaterThanOrEqualTo(0);

    // Derived invariant — can't confirm more orders than stock supports
    assertThat(successes).isLessThanOrEqualTo(stock / qty); // ≤ 20
}
```

If this test flakes — passes sometimes, fails sometimes — the optimistic locking
or retry implementation is wrong. Reduce thread count or increase retry attempts
to debug, then restore the original parameters. Do not paper over a flaky test
by adjusting assertions.

---

## SQL Logging — Turn On Before Phase 2

Enable during development to see exactly what Hibernate fires. Turn off before
load tests — logging has measurable throughput impact:

```yaml
# application.yml (dev profile only)
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE  # shows parameter values bound to ?
```

On a `GET /orders/{id}` you should see exactly one SQL with a `LEFT JOIN` on
`order_items` — not two separate SELECTs. On an `orderRepository.save()` for an
update you should see a `SELECT` followed by an `UPDATE` — not two INSERTs.
If you see otherwise, the mapper or adapter fix did not land correctly.
