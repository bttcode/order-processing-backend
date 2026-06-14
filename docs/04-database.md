# 04 — Database

<!--
MASTER SECTIONS COVERED
  § DB-1: Schema Design (full DDL for all tables + indexes)
  § DB-2: Optimistic Locking (JPA @Version, retry, race condition test)
  § DB-3: Transaction Isolation Testing

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4)  : DB-1 — schema creation via Flyway migration scripts,
                         basic CRUD verified
  Phase 2 (Weeks 5–8)  : DB-2 — optimistic locking wired in for inventory,
                         concurrent order test passing
  Phase 3 (Weeks 9–12) : EXPLAIN ANALYZE for every hot-path query,
                         composite index validation
  Phase 4 (Weeks 13–16): DB-3 — isolation level tests written and passing

OPEN ISSUES FROM SPLIT REVIEW
  [OI-8]  REPEATABLE_READ must be set explicitly on inventory transactions via
          @Transactional(isolation = Isolation.REPEATABLE_READ). It is NOT
          inherited from a parent READ_COMMITTED transaction. DB-3 tests
          verify this is actually enforced.
  [OI-10] This file is the single source of truth for all DDL, including
          indexes. PER-3 in 03-performance.md references index strategy
          rationale but contains no CREATE INDEX SQL.
  [OI-12] The products table uses VARCHAR(50) as primary key. At 10k orders/sec
          this is a hot write table. If product ID is a random string, consider
          a hash index on the PK or using a BIGSERIAL surrogate key for the FK
          in order_items. Deferred decision — profile in Phase 3 before changing.
  [OI-13] Flyway vs Liquibase: pick one in Phase 1 and commit. Flyway is
          simpler for a solo project; Liquibase adds XML overhead that pays off
          in team environments. Recommend Flyway. Document the decision.
-->

---

## DB-1: Schema Design

All DDL must be managed through migration scripts (Flyway recommended — see
`[OI-13]`). File naming: `V{n}__{description}.sql` in
`src/main/resources/db/migration/`.

### orders

```sql
CREATE TABLE orders (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id            VARCHAR(50)   NOT NULL,
    status                 VARCHAR(30)   NOT NULL,
    total_amount           DECIMAL(12,2) NOT NULL,
    currency               VARCHAR(3)    NOT NULL DEFAULT 'USD',
    payment_method         VARCHAR(20),
    payment_transaction_id VARCHAR(100),
    idempotency_key        UUID          UNIQUE,
    created_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    confirmed_at           TIMESTAMP,
    version                INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT chk_order_status CHECK (status IN (
        'PENDING_VALIDATION',
        'PENDING_PAYMENT',
        'PAYMENT_AUTHORIZED',
        'CONFIRMED',
        'CANCELLED',
        'INSUFFICIENT_INVENTORY',
        'PAYMENT_FAILED'
    ))
);

-- Primary access patterns:
-- 1. Fetch by order ID (PK — no extra index needed)
-- 2. Order history per customer, newest first
CREATE INDEX idx_order_customer_date
    ON orders(customer_id, created_at DESC);

-- 3. Status filtering (partial: skip terminal DELIVERED state)
CREATE INDEX idx_order_status
    ON orders(status)
    WHERE status NOT IN ('CONFIRMED', 'CANCELLED');

-- 4. Idempotency key lookup
CREATE INDEX idx_order_idempotency
    ON orders(idempotency_key);

-- 5. Composite for the full order history query
--    (customer_id = ? AND status = ? ORDER BY created_at DESC)
CREATE INDEX idx_order_composite
    ON orders(customer_id, status, created_at DESC);
```

### order_items

```sql
CREATE TABLE order_items (
    id            BIGSERIAL     PRIMARY KEY,
    order_id      UUID          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id    VARCHAR(50)   NOT NULL,
    product_name  VARCHAR(200)  NOT NULL,
    quantity      INTEGER       NOT NULL,
    unit_price    DECIMAL(12,2) NOT NULL,
    total_price   DECIMAL(12,2) NOT NULL,

    CONSTRAINT chk_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_order_item_order   ON order_items(order_id);
CREATE INDEX idx_order_item_product ON order_items(product_id);
```

### products

```sql
CREATE TABLE products (
    id                 VARCHAR(50)   PRIMARY KEY,
    sku                VARCHAR(100)  UNIQUE NOT NULL,
    name               VARCHAR(200)  NOT NULL,
    description        TEXT,
    price              DECIMAL(12,2) NOT NULL,
    inventory_quantity INTEGER       NOT NULL DEFAULT 0,
    version            INTEGER       NOT NULL DEFAULT 0,   -- optimistic lock
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_price     CHECK (price >= 0),
    CONSTRAINT chk_inventory CHECK (inventory_quantity >= 0)
);

-- SKU lookup (product validation in FR-1)
CREATE INDEX idx_product_sku ON products(sku);
```

> **[OI-12]** `products.id` is `VARCHAR(50)`. Profile the FK join
> `order_items.product_id → products.id` in Phase 3. If it shows up as a
> bottleneck, consider adding a `BIGSERIAL` surrogate key. Do not change
> the schema without an ADR documenting the impact on the domain model.

### idempotency_keys

```sql
CREATE TABLE idempotency_keys (
    key             UUID      PRIMARY KEY,
    response_body   TEXT      NOT NULL,
    response_status INTEGER   NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL
);

-- Used by the cleanup job to find expired rows efficiently
CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);
```

**Cleanup job requirement:** rows where `expires_at < NOW()` must be purged
regularly. Implement as a `@Scheduled` task running every hour (configurable).
See `[OI-6]` in `01-functional.md § FR-8`.

### Schema notes

- `DECIMAL(12,2)` on all monetary columns — never use `FLOAT` or `DOUBLE` for
  money.
- `version` on both `orders` and `products` — used for optimistic locking at
  the JPA layer; do not bypass by using native queries that omit the version
  increment.
- `updated_at` should be maintained by an `ON UPDATE` trigger or application
  code — do not leave it stale.

---

## DB-2: Optimistic Locking

### JPA entity (`ProductEntity`)

```java
@Entity
@Table(name = "products")
public class ProductEntity {

    @Id
    private String id;

    @Column(name = "inventory_quantity")
    private Integer inventoryQuantity;

    @Version
    private Integer version;
    // Hibernate auto-increments version on every UPDATE.
    // If the version in the DB has changed since the entity was loaded,
    // Hibernate throws OptimisticLockException instead of committing.
}
```

### Service with retry on version conflict

```java
@Service
public class InventoryService {

    @Transactional(isolation = Isolation.REPEATABLE_READ)  // see [OI-8]
    @Retryable(maxAttempts = 3, value = OptimisticLockException.class)
    public void reserveInventory(String productId, int quantity) {
        ProductEntity product = productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getInventoryQuantity() < quantity) {
            throw new InsufficientInventoryException(productId, quantity);
        }

        product.setInventoryQuantity(product.getInventoryQuantity() - quantity);
        productRepository.save(product); // version check fired on flush
    }
}
```

**What happens under high concurrency:**

1. Thread A reads `product` with `version = 5`, `quantity = 100`
2. Thread B reads `product` with `version = 5`, `quantity = 100`
3. Thread A saves → Hibernate issues:
   `UPDATE products SET inventory_quantity=85, version=6 WHERE id=? AND version=5` → 1 row updated ✓
4. Thread B saves → same SQL with `version=5` → 0 rows updated → `OptimisticLockException`
5. Thread B retries: reloads from DB (now sees `version=6`, `quantity=85`),
   attempts reservation — succeeds or fails on real stock

### Race condition test (from `06-testing.md § TEST-4`)

```java
@Test
void testConcurrentInventoryUpdates() throws Exception {
    Product product = createProduct("P1", 100);

    // 10 threads, each requesting 15 units → total demand 150, only 100 available
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<Order>> futures = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
        futures.add(executor.submit(() ->
            orderService.createOrder(createOrderRequest("P1", 15))
        ));
    }

    List<Order> successful = futures.stream()
        .map(this::getFuture)
        .filter(order -> order.getStatus() == CONFIRMED)
        .collect(Collectors.toList());

    // Only 6 can succeed (6 * 15 = 90 ≤ 100); 7th would need 105 — insufficient
    assertThat(successful).hasSizeLessThanOrEqualTo(6);

    // Final inventory must be ≥ 0 — never negative (overselling = bug)
    Product final_ = productRepository.findById("P1").orElseThrow();
    assertThat(final_.getInventoryQuantity()).isGreaterThanOrEqualTo(0);
}
```

This test is a hard correctness gate. If it flakes or fails under any
concurrency configuration, the inventory implementation is wrong — do not
work around it by reducing thread count.

---

## DB-3: Transaction Isolation Testing

These tests verify that the isolation levels declared in `02-architecture.md
§ AR-4` are actually enforced by PostgreSQL. They require Testcontainers
(see `06-testing.md § TEST-2`).

### Test 1 — READ_COMMITTED prevents dirty reads

```java
@Test
void testReadCommitted_PreventsDirtyReads() {
    // Setup: create an order with status PENDING_PAYMENT

    // Tx1: Update order status to CONFIRMED but do NOT commit
    //      (simulate with manual transaction control / two-thread approach)

    // Tx2 (READ_COMMITTED): read order status
    //      Expected: sees PENDING_PAYMENT (the last committed value)
    //      Must NOT see CONFIRMED from the uncommitted Tx1
}
```

### Test 2 — REPEATABLE_READ prevents non-repeatable reads on inventory

```java
@Test
void testRepeatableRead_PreventsNonRepeatableReads() {
    // Setup: product with 100 units

    // Tx1 (REPEATABLE_READ): read inventory → sees 100

    // Tx2: deduct 20 units and COMMIT → inventory now 80 in DB

    // Tx1: read inventory again within the same transaction
    //      Expected: still sees 100 (snapshot from transaction start)
    //      This guarantees the reservation logic sees a stable view
}
```

### Test 3 — Serializable prevents phantom reads

```java
@Test
void testSerializable_PreventsPhantomReads() {
    // Setup: 3 PENDING orders in DB

    // Tx1 (SERIALIZABLE): COUNT(*) WHERE status='PENDING_PAYMENT' → 3

    // Tx2: INSERT a new PENDING_PAYMENT order and COMMIT

    // Tx1: COUNT(*) again
    //      Expected: still 3 (phantom row not visible within snapshot)
    //      Demonstrates Serializable behaviour for reporting use cases
}
```

> **[OI-8]** Test 2 is the critical one. If it fails, it means `REPEATABLE_READ`
> is not being applied to the inventory service transaction — check the
> `@Transactional(isolation = Isolation.REPEATABLE_READ)` annotation is present
> and not being proxied away (a common issue with self-invocation through `this`
> rather than the Spring proxy).

### EXPLAIN ANALYZE requirement

For every query exercised by the integration test suite, capture the execution
plan at 100,000+ rows:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT * FROM orders
WHERE customer_id = 'CUST-123'
  AND status = 'CONFIRMED'
ORDER BY created_at DESC
LIMIT 20;
```

Expected output: `Index Scan using idx_order_composite` — not `Seq Scan`.
If `Seq Scan` appears, the index is not being used; check column order and
operator compatibility before adding a new index.