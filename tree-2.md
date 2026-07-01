src/test/java/com/bowt/backend/orderprocessing/
├── architecture/
│   └── ArchitectureTest.java              # ArchUnit — layer rule, no-Spring-in-domain, pk leakage
│
├── domain/                                 # plain JUnit 5, NO Spring context, NO Mockito needed
│   ├── model/
│   │   ├── OrderTest.java                  # state machine, reconstruct(), no @Setter leakage
│   │   ├── OrderItemTest.java
│   │   ├── OrderStatusTest.java            # all valid + all invalid transitions
│   │   ├── MoneyTest.java
│   │   └── ProductTest.java                # deductStock()/restoreStock() invariants (P9)
│   ├── factory/
│   │   └── OrderFactoryTest.java           # each OrderType → correct object, validation rules
│   └── decorator/
│       └── OrderEnhancementChainTest.java  # decorators compose correctly, order-independent where required
│
├── application/                            # JUnit 5 + Mockito — mock every out-port, no real DB/Spring context
│   ├── service/
│   │   ├── OrderServiceTest.java           # orchestration logic, reactive pipeline error paths (P6 regression test)
│   │   ├── InventoryServiceTest.java       # reservation logic, retry-on-OptimisticLockException path
│   │   ├── PaymentServiceTest.java         # timeout handling, executor usage (P7 regression test)
│   │   └── AuditServiceTest.java           # REQUIRES_NEW behavior — can be asserted via mock invocation, not real tx
│   ├── listener/
│   │   └── EventDispatchTest.java          # a listener throwing must NOT break the pipeline (NFR-3 isolation)
│   └── usecase/
│       └── CreateOrderUseCaseContractTest.java  # verifies the use-case interface contract independent of impl
│
├── infrastructure/
│   ├── persistence/
│   │   ├── OrderJpaAdapterIntegrationTest.java   # Testcontainers — save/find round trip, N+1 regression check (I-2)
│   │   ├── ProductJpaAdapterIntegrationTest.java
│   │   ├── IdempotencyJpaAdapterIntegrationTest.java  # Phase 5 — store/find/expire
│   │   └── IdempotencyCleanupJobTest.java        # expired rows actually purged
│   ├── payment/
│   │   ├── MockPaymentGatewayTest.java
│   │   └── PaymentStrategyFactoryTest.java       # correct strategy resolved per PaymentMethod enum
│   ├── rest/
│   │   ├── OrderControllerV1IntegrationTest.java # MockMvc/WebTestClient, full stack incl. interceptors
│   │   ├── OrderControllerV2IntegrationTest.java # confirms totalAmount→total per OI-14
│   │   ├── GlobalExceptionHandlerTest.java       # regression test for I-3/I-4 status-code bugs specifically
│   │   ├── ApiKeyInterceptorTest.java
│   │   ├── RateLimitInterceptorTest.java
│   │   └── DeprecationInterceptorTest.java       # Warning header present on /v1/ only
│   └── isolation/
│       └── TransactionIsolationTest.java         # DB-3 — Testcontainers, the 3 isolation-level tests
│
├── concurrency/
│   └── ConcurrentInventoryTest.java         # TEST-4 — 50 threads, CountDownLatch, <= assertion (OI-17)
│
├── idempotency/
│   └── IdempotencyEndToEndTest.java         # TEST-5 — full stack: replay, conflict, cancel-replay
│
└── performance/                             # @Tag("performance") — separate Maven profile, NOT in default run
└── OrderProcessingSlaTest.java              # TEST-3 in-code regression gate (p50/p95/p99 assertions)