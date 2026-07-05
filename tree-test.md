src/test/java/com/bowt/backend/orderprocessing/
│
├── domain/                                   # TEST-1 — plain JUnit 5, NO Spring context
│   ├── model/
│   │   ├── OrderTest.java                    # lifecycle transitions, addItem validation,
│   │   │                                     # confirm(), 100% branch on state machine calls
│   │   ├── OrderStatusTest.java              # canTransitionTo() — every valid + invalid edge
│   │   │                                     # (this is the 100%-branch critical-path file)
│   │   ├── OrderItemTest.java                # positive/zero/negative quantity, totalPrice calc
│   │   ├── ProductTest.java                  # deductStock/restoreStock invariants (post domain-review fix)
│   │   ├── MoneyTest.java                    # add/subtract/compare, currency mismatch, scale
│   │   └── ShippingAddressTest.java          # basic value object equality
│   ├── factory/
│   │   └── OrderFactoryTest.java             # StandardOrder/ExpressOrder/SubscriptionOrder construction (AR-2 #2)
│   └── event/
│       └── OrderEventTest.java               # payload shape, equals/hashCode if used as key
│
├── application/                              # use-case tests — mock OUT ports only
│   ├── service/
│   │   ├── OrderServiceTest.java             # mocks OrderRepository, InventoryService,
│   │   │                                     # PaymentService, OrderEventPublisher;
│   │   │                                     # covers happy path + every failure branch
│   │   │                                     # (INSUFFICIENT_INVENTORY, PAYMENT_FAILED)
│   │   ├── InventoryServiceTest.java         # mocks ProductRepository; reservation math,
│   │   │                                     # OptimisticLockException → retry → INSUFFICIENT_INVENTORY
│   │   ├── PaymentServiceTest.java           # mocks PaymentGateway; 5s timeout, retry/backoff,
│   │   │                                     # exhausted-retry → PAYMENT_FAILED
│   │   └── AuditServiceTest.java             # REQUIRES_NEW behavior mocked at repo level
│   ├── listener/
│   │   ├── WarehouseListenerTest.java        # onOrderStatusChanged behavior
│   │   ├── AccountingListenerTest.java
│   │   └── CustomerNotificationListenerTest.java
│   │                                         # + one test proving a listener throwing does NOT
│   │                                         #   fail the main pipeline (NFR-3 isolation requirement)
│   └── config/
│       └── UseCaseConfigurationTest.java     # @SpringBootTest slice: asserts CreateOrderUseCase
│                                             # and QueryOrderUseCase resolve to the SAME
│                                             # OrderService bean instance (regression test for
│                                             # the two-instance bug found in session review)
│
├── infrastructure/
│   ├── persistence/
│   │   ├── AbstractIntegrationTest.java      # shared Testcontainers PG15 singleton base class
│   │   ├── adapter/
│   │   │   ├── OrderJpaAdapterIntegrationTest.java   # save/findByPublicId, index usage
│   │   │   ├── ProductJpaAdapterIntegrationTest.java # @Version bump on save
│   │   │   └── IdempotencyJpaAdapterIntegrationTest.java
│   │   ├── jpa/
│   │   │   └── JpaOrderRepositoryQueryTest.java      # @DataJpaTest — verifies JOIN FETCH,
│   │   │                                             # findAllByBusinessIds batch fix (I-2)
│   │   ├── mapper/
│   │   │   ├── OrderMapperTest.java          # plain unit test — toDomain/toEntity round trip;
│   │   │   │                                 # after Order.reconstruct() fix, asserts no
│   │   │   │                                 # setStatus() call is needed
│   │   │   └── ProductMapperTest.java
│   │   ├── DataSeederIntegrationTest.java    # CommandLineRunner fires post-context, idempotent reseed
│   │   └── scheduled/
│   │       └── IdempotencyCleanupJobIntegrationTest.java  # expired rows purged, live rows kept
│   │
│   ├── payment/
│   │   ├── MockPaymentGatewayTest.java       # failure-rate injection, timeout simulation
│   │   ├── CreditCardPaymentStrategyTest.java
│   │   ├── PayPalPaymentStrategyTest.java
│   │   ├── CryptoPaymentStrategyTest.java
│   │   └── PaymentStrategyFactoryTest.java   # correct strategy resolved per PaymentMethod enum
│   │
│   ├── notification/
│   │   └── EventNotificationAdapterTest.java # async dispatch, subscriber exception isolation
│   │
│   ├── config/
│   │   ├── ExecutorConfigurationTest.java    # bean params (core/max/queue/policy) as configured
│   │   └── RetryAspectTest.java              # @Retryable fires only on declared on() types (P10),
│   │                                         # exhausts maxAttempts, linear backoff timing
│   │
│   └── rest/
│       ├── v1/
│       │   ├── OrderControllerV1IntegrationTest.java  # MockMvc: 201/200/400/404/422 per API-2
│       │   └── OrderResponseMapperV1Test.java         # totalAmount field name (API-3)
│       ├── v2/
│       │   ├── OrderControllerV2IntegrationTest.java  # `total` field, page metadata (API-3)
│       │   └── OrderResponseMapperV2Test.java
│       ├── exception/
│       │   └── GlobalExceptionHandlerTest.java        # regression test for I-3/I-4: HTTP status
│       │                                              # on the wire MUST equal ProblemDetail.status
│       │                                              # for every exception type in API-2 table
│       ├── idempotency/
│       │   └── IdempotencyHandlerTest.java            # cache-hit vs cache-miss branch, 409 on conflict
│       ├── ratelimit/
│       │   ├── RateLimitInterceptorTest.java          # 429 + headers on exhaustion
│       │   └── DeprecationInterceptorTest.java        # Warning header present on /v1 only
│       └── security/
│           └── ApiKeyInterceptorTest.java             # 401 on missing/invalid key, health exempt
│
├── concurrency/                              # TEST-4 — correctness under contention
│   ├── ConcurrentInventoryTest.java           # 50 threads × 5 units vs 100 stock;
│   │                                         # asserts finalInventory >= 0 AND
│   │                                         # successCount <= 20 (OI-17 — no hard ==20)
│   ├── ConcurrentOrderCancellationTest.java   # two threads cancel same order → exactly one wins
│   └── ConcurrentIdempotencyKeyTest.java      # two threads, same key, same payload →
│                                              # exactly one execution, identical response
│
├── idempotency/                              # TEST-5
│   ├── IdempotentOrderCreationTest.java       # 201 → replay → 200, same orderId, 1 row in DB
│   ├── IdempotencyKeyConflictTest.java        # same key, different payload → 409
│   └── IdempotentCancellationTest.java        # replay does not call refund() twice (verify(times(1)))
│
├── isolation/                                 # DB-3 — Testcontainers, two-thread manual control
│   ├── ReadCommittedTest.java                 # Test 1 — dirty read prevented
│   ├── RepeatableReadInventoryTest.java       # Test 2 — the critical one; also proves
│   │                                          # REQUIRES_NEW fix (P5/OI-8) actually applies
│   │                                          # isolation instead of inheriting READ_COMMITTED
│   └── SerializablePhantomReadTest.java       # Test 3 — reporting query snapshot
│
├── architecture/                              # ArchUnit — run in CI, fails the build on violation
│   ├── LayerDependencyTest.java               # infrastructure → application → domain only
│   ├── DomainPurityTest.java                  # zero org.springframework.*, jakarta.*,
│   │                                          # com.fasterxml.uuid.* imports under domain/
│   ├── PkLeakageTest.java                     # `pk` field never declared outside
│   │                                          # infrastructure.persistence (ADR-001 rule)
│   └── PortNamingConventionTest.java          # every adapter implements exactly one port (NFR-4 SOLID gate)
│
└── performance/                                # TEST-3 — tagged, excluded from default `mvn test`
    └── PerformanceRegressionTest.java          # @Tag("performance"); p50<30ms p95<75ms p99<100ms
# on N sequential createOrder calls — CI smoke gate,
# NOT a replacement for real JMeter runs

src/test/resources/
├── application-test.yml                        # test profile — points at Testcontainers-injected props
└── db/
    └── (optional) cleanup.sql                  # @Sql scripts for integration test isolation

load-tests/                                      # OUTSIDE src/test — not JUnit, not compiled by Maven
├── scenario1-baseline.jmx                       # 1,000 orders/sec × 10 min (warm-up, not SLA — OI-16)
├── scenario2-peak.jmx                           # 10,000 orders/sec × 5 min — the real NFR-1 SLA gate
├── scenario3-endurance.jmx                      # 1,000 orders/sec × 2h (or 72h) — leak detection
└── scenario4-spike.jmx                          # 100→10,000→100 orders/sec — backpressure/recovery