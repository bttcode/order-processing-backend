# 06 — Testing

<!--
MASTER SECTIONS COVERED
  § TEST-1: Unit Testing (coverage targets, domain layer examples)
  § TEST-2: Integration Testing (Testcontainers, full stack)
  § TEST-3: Performance Testing (JMeter load scenarios)
  § TEST-4: Concurrency Testing (race condition tests)
  § TEST-5: Idempotency Testing

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4)  : TEST-1 domain unit tests written alongside code;
                         TEST-2 basic integration test for order creation
  Phase 2 (Weeks 5–8)  : TEST-4 concurrency tests — must pass before Phase 3
  Phase 3 (Weeks 9–12) : TEST-3 JMeter load scenarios run and results recorded
  Phase 5 (Weeks 17–20): TEST-5 idempotency tests
  Phase 6 (Weeks 21–24): Full test suite clean, coverage gates enforced in CI

OPEN ISSUES FROM SPLIT REVIEW
  [OI-16] TEST-3 Scenario 1 target is "1000 orders/sec" but NFR-1 requires
          10,000 orders/sec sustained. The JMeter scenarios are a graduated
          ramp — Scenario 1 is a warm-up baseline, not the final target.
          Scenario 4 (spike test) is the closest to the real SLA gate.
          Do not report Scenario 1 results as "meeting the SLA."
  [OI-17] TEST-4 asserts exactly `successCount == 20` and `failureCount == 30`
          for a 50-thread × 5-unit test against 100-unit inventory. The exact
          numbers assume no partial fills and a clean retry failure path. If
          the retry logic in DB-2 allows some threads to succeed after an
          initial collision, the final count could differ from 20. The hard
          invariant is `finalInventory >= 0`. Adjust the assertion if the
          implementation allows more than 20 successes due to retry semantics —
          but zero overselling must always hold.
  [OI-18] TEST-5 uses `HttpStatus.OK` (200) for the idempotent replay but
          `HttpStatus.CREATED` (201) for the first call. The controller must
          explicitly return 200 for cache hits — Spring's default `@PostMapping`
          returns 200 unless `ResponseEntity.created()` is used. Verify this
          in the implementation before writing the test assertion.
-->

---

## Coverage targets

| Layer | Minimum line coverage | Branch coverage |
|---|---|---|
| `domain/` | > 80% | 100% on critical paths (order lifecycle, inventory) |
| `application/` | > 70% | — |
| `infrastructure/` | > 60% | — |
| Overall | > 70% | — |

"Critical paths" for 100% branch coverage: the order status state machine,
inventory reservation logic, payment retry/failure paths.

---

## TEST-1: Unit Testing

**Rules:**
- Domain layer tests load zero Spring context — plain JUnit 5 only
- No mocking frameworks needed for the domain in isolation (verify this)
- Infrastructure unit tests mock only the immediate port, not the whole stack

### Domain model tests

```java
class OrderTest {

    @Test
    void shouldCalculateTotalAmountCorrectly() {
        Order order = new Order("CUST-123");
        order.addItem(new OrderItem("PROD-1", 2, Money.of(49.99)));
        order.addItem(new OrderItem("PROD-2", 1, Money.of(199.99)));

        assertThat(order.getTotalAmount()).isEqualTo(Money.of(299.97));
    }

    @Test
    void shouldRejectNegativeQuantity() {
        Order order = new Order("CUST-123");

        assertThatThrownBy(() ->
            order.addItem(new OrderItem("PROD-1", -5, Money.of(49.99)))
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Quantity must be positive");
    }

    @Test
    void shouldRejectZeroQuantity() {
        Order order = new Order("CUST-123");

        assertThatThrownBy(() ->
            order.addItem(new OrderItem("PROD-1", 0, Money.of(49.99)))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTransitionFromPendingPaymentToConfirmed() {
        Order order = new Order("CUST-123");
        order.setStatus(OrderStatus.PENDING_PAYMENT);

        order.confirm("TXN-12345");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();
    }

    @Test
    void shouldRejectConfirmationFromInvalidState() {
        Order order = new Order("CUST-123");
        order.setStatus(OrderStatus.PAYMENT_FAILED);

        assertThatThrownBy(() -> order.confirm("TXN-12345"))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

### What to cover with unit tests beyond the above

- `Money` value object: addition, subtraction, comparison, currency mismatch
- `OrderStatus` state machine: all valid transitions, all invalid transitions
- `OrderFactory`: each order type produces the correct domain object
- `InventoryService` domain logic: reservation, release, permanent deduction
- Payment strategy selection logic

---

## TEST-2: Integration Testing

**Rules:**
- Use Testcontainers — real PostgreSQL 15, no H2 substitutes for integration tests
- H2 is acceptable for fast unit tests in `domain/` and `application/`
- Each integration test class starts with a clean database state
  (use `@Transactional` + rollback, or `@Sql` cleanup scripts)

```java
@SpringBootTest
@Testcontainers
class OrderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;

    @Test
    void shouldCreateOrderSuccessfully() {
        // Arrange
        Product product = new Product("PROD-1", "Test Product", Money.of(99.99), 50);
        productRepository.save(product);

        OrderRequest request = OrderRequest.builder()
            .customerId("CUST-123")
            .addItem("PROD-1", 2)
            .paymentMethod(PaymentMethod.CREDIT_CARD)
            .build();

        // Act
        Order order = orderService.createOrder(request);

        // Assert
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getTotalAmount()).isEqualTo(Money.of(199.98));

        Product updated = productRepository.findById("PROD-1").orElseThrow();
        assertThat(updated.getInventoryQuantity()).isEqualTo(48);
    }
}
```

### Required integration test scenarios

- Successful order creation → inventory deducted, status CONFIRMED
- Order creation with unknown product ID → 422, inventory unchanged
- Order creation with insufficient inventory → INSUFFICIENT_INVENTORY, no payment called
- Payment gateway failure → PAYMENT_FAILED, inventory reservation released
- Order cancellation → status CANCELLED, inventory restored, refund recorded
- Idempotent order creation (covered in TEST-5)

---

## TEST-3: Performance Testing

All scenarios run with **JMeter** (or Gatling) against a local stack
(`docker-compose up`). Results feed directly into `DEL-4` (performance
evidence) in `07-delivery.md`.

> **[OI-16]** Scenarios are graduated — Scenario 1 is a baseline warmup, not
> the final SLA gate. The NFR-1 SLA (10,000 orders/sec sustained) is tested
> in Scenario 2. Report all four scenarios in the performance report.

### Scenario 1 — Baseline throughput

| Setting | Value |
|---|---|
| Ramp-up | 100 threads over 60 seconds |
| Sustained load | 1,000 orders/sec for 10 minutes |
| Measure | Throughput (orders/sec), p50/p95/p99 latency, error rate |

Pass criterion: error rate < 0.1%, p99 < 100ms.

### Scenario 2 — Peak load (NFR-1 SLA gate)

| Setting | Value |
|---|---|
| Ramp-up | 500 threads over 120 seconds |
| Peak load | 10,000 orders/sec for 5 minutes |
| Measure | As above + active thread count, queue depth |

Pass criterion: throughput ≥ 10,000/sec sustained, error rate < 0.1%.

### Scenario 3 — Endurance test (memory leak detection)

| Setting | Value |
|---|---|
| Sustained load | 1,000 orders/sec for 2 hours |
| Monitor | Heap growth trend, GC frequency, connection pool exhaustion |

Pass criterion: heap stabilises (does not grow linearly after JVM warm-up).

### Scenario 4 — Spike test (backpressure and recovery)

| Setting | Value |
|---|---|
| Baseline | 100 orders/sec |
| Spike | 10,000 orders/sec for 30 seconds |
| Recovery | Return to 100 orders/sec |
| Measure | Error rate during spike, recovery time to baseline latency |

Pass criterion: system returns to SLA latency within 60 seconds of spike end,
no unrecoverable state (no stuck threads, no leaked connections).

### In-code performance assertion (regression gate)

```java
@Test
void performanceTest_ShouldMeetSLA() {
    List<Long> responseTimes = new ArrayList<>();

    for (int i = 0; i < 10_000; i++) {
        long start = System.nanoTime();
        orderService.createOrder(generateRandomOrder());
        responseTimes.add((System.nanoTime() - start) / 1_000_000L);
    }

    Collections.sort(responseTimes);
    int n = responseTimes.size();

    long p50 = responseTimes.get(n / 2);
    long p95 = responseTimes.get((int)(n * 0.95));
    long p99 = responseTimes.get((int)(n * 0.99));

    assertThat(p50).isLessThan(30);   // NFR-1 SLA
    assertThat(p95).isLessThan(75);
    assertThat(p99).isLessThan(100);
}
```

This test is a CI regression gate — it must run in a consistent environment
(not a developer laptop). Tag it `@Tag("performance")` and exclude from
the default Surefire run; include it in a dedicated Maven profile.

---

## TEST-4: Concurrency Testing

These are correctness tests, not performance tests. Their primary purpose is
to prove that the optimistic locking and retry logic in `04-database.md § DB-2`
prevents overselling under any concurrency level.

```java
@Test
void testConcurrentOrdersForSameProduct_ShouldNotOversell() throws Exception {
    int concurrentOrders = 50;
    int quantityPerOrder = 5;    // total demand = 250, stock = 100
    int initialInventory = 100;

    Product product = createProduct("PROD-1", initialInventory);

    ExecutorService executor = Executors.newFixedThreadPool(concurrentOrders);
    CountDownLatch startGate = new CountDownLatch(concurrentOrders);
    List<Future<OrderResult>> futures = new ArrayList<>();

    for (int i = 0; i < concurrentOrders; i++) {
        futures.add(executor.submit(() -> {
            startGate.countDown();
            startGate.await(); // synchronised start — maximises contention
            try {
                Order order = orderService.createOrder(
                    createOrderRequest("PROD-1", quantityPerOrder));
                return OrderResult.success(order);
            } catch (Exception e) {
                return OrderResult.failure(e.getMessage());
            }
        }));
    }

    int successCount = 0;
    int failureCount = 0;
    for (Future<OrderResult> f : futures) {
        OrderResult result = f.get(10, TimeUnit.SECONDS);
        if (result.isSuccess()) successCount++;
        else failureCount++;
    }

    // Hard invariant: final inventory must be >= 0
    Product finalProduct = productRepository.findById("PROD-1").orElseThrow();
    assertThat(finalProduct.getInventoryQuantity())
        .as("Inventory must never go negative (oversell)")
        .isGreaterThanOrEqualTo(0);

    // Derived invariant: successful orders must not exceed available stock
    int maxSuccessAllowed = initialInventory / quantityPerOrder; // = 20
    assertThat(successCount)
        .as("Successful orders must not exceed stock capacity")
        .isLessThanOrEqualTo(maxSuccessAllowed);
}
```

> **[OI-17]** The original spec asserts `successCount == 20` exactly. With
> optimistic locking retries, some threads may succeed after initially
> colliding, so the exact count could be anywhere from 1 to 20. The invariants
> that must always hold are: `finalInventory >= 0` and
> `successCount <= initialInventory / quantityPerOrder`. Adjust the
> assertion as shown above rather than hard-coding 20.

### Additional concurrency scenarios to cover

- Two threads cancelling the same order simultaneously → exactly one succeeds,
  one gets 409 or 422
- Idempotency key submitted by two threads simultaneously → exactly one
  operation executes, both return the same response
- Payment gateway timeout occurring during high concurrency → order transitions
  to PAYMENT_FAILED, inventory released

---

## TEST-5: Idempotency Testing

```java
@Test
void testIdempotentOrderCreation_ReplayReturnsSameOrder() {
    String idempotencyKey = UUID.randomUUID().toString();
    OrderRequest request = createOrderRequest("PROD-1", 2);

    // First call
    ResponseEntity<OrderResponse> first =
        orderController.createOrder(request, idempotencyKey);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);   // 201

    // Replay with same key and same payload
    ResponseEntity<OrderResponse> second =
        orderController.createOrder(request, idempotencyKey);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);       // 200, not 201
    assertThat(second.getBody().getOrderId())
        .isEqualTo(first.getBody().getOrderId());

    // Only one order created in the database
    List<Order> allOrders = orderRepository.findAll();
    assertThat(allOrders).hasSize(1);
}

@Test
void testIdempotencyKeyConflict_DifferentPayloadReturns409() {
    String idempotencyKey = UUID.randomUUID().toString();

    // First call: create an order for PROD-1
    orderController.createOrder(createOrderRequest("PROD-1", 2), idempotencyKey);

    // Second call: same key, different payload (PROD-2)
    ResponseEntity<OrderResponse> conflict =
        orderController.createOrder(createOrderRequest("PROD-2", 5), idempotencyKey);

    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // 409
}

@Test
void testIdempotentCancellation_ReplayReturnsSameCancellation() {
    // Create and confirm an order first
    Order order = createConfirmedOrder();
    String idempotencyKey = UUID.randomUUID().toString();

    // First cancellation
    ResponseEntity<CancellationResponse> first =
        orderController.cancelOrder(order.getId(), new CancelRequest("test"), idempotencyKey);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Replay — must not trigger a second refund
    ResponseEntity<CancellationResponse> second =
        orderController.cancelOrder(order.getId(), new CancelRequest("test"), idempotencyKey);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().getRefundTransactionId())
        .isEqualTo(first.getBody().getRefundTransactionId());

    // Verify: payment gateway refund called exactly once
    verify(paymentGateway, times(1)).refund(any(), any());
}
```

> **[OI-18]** The `201 → 200` status change on replay requires the controller
> to check the idempotency store before calling the use case. If the key
> exists, return the cached response with `ResponseEntity.ok(cachedBody)`
> rather than `ResponseEntity.created(uri).body(newBody)`. Verify in Phase 5
> that the controller path correctly sets the status code, not the use case.