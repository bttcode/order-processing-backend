# 02 — Architecture

<!--
MASTER SECTIONS COVERED
  § AR-1: Hexagonal Architecture (Ports and Adapters)
  § AR-2: Design Pattern Requirements
  § AR-3: Concurrency Architecture
  § AR-4: Transaction Management
  § AR-5: Spring Framework Deep Dive Requirements
  § TR-3: Project Structure
  § NFR-2: Scalability
  § NFR-3: Reliability
  § NFR-4: Maintainability

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4)  : TR-3 structure, basic Spring Boot skeleton
  Phase 2 (Weeks 5–8)  : AR-3 (concurrency — thread pools, reactive pipeline)
  Phase 4 (Weeks 13–16): AR-1 refactor to hexagonal, AR-2 design patterns,
                         AR-4 transaction strategy, AR-5 Spring internals
  All phases           : NFR-2, NFR-3, NFR-4 are non-negotiable constraints
                         that must be upheld from Phase 1 onwards, not retrofitted

OPEN ISSUES FROM SPLIT REVIEW
  [OI-1] NFR-2, NFR-3, NFR-4 had no home in the original split plan.
         They are placed here because they constrain architectural decisions
         (stateless design, circuit breakers, SOLID) rather than API or
         performance tuning.
  [OI-7] AR-3 shows a single global RateLimiter in API-4 (05-api.md) but
         AR-3 also defines per-executor backpressure. These are different
         mechanisms — ensure the rate limiter in the HTTP layer and the
         CallerRunsPolicy in the thread pool are both understood as
         complementary, not duplicates.
  [OI-8] AR-4 lists REPEATABLE_READ for inventory updates but PostgreSQL's
         default isolation for a @Transactional is READ_COMMITTED. The
         REPEATABLE_READ level must be set explicitly on the inventory
         transaction — it will not be inherited. See 04-database.md § DB-3
         for the test scenarios that verify this.
  [OI-9] AR-5 RetryAspect calls Thread.sleep() inside an @Around advice.
         This blocks the carrier thread. If the reactive pipeline (AR-3) is
         active, use Mono.delay() or Resilience4j's reactive retry instead.
         Decide in Phase 2 which retry path is canonical and ensure both
         AR-5 and FR-3 are consistent.
-->

---

## NFR-2: Scalability (constraint)

These constraints must hold from Phase 1. They are architectural decisions, not
performance tuning tasks.

**Horizontal scaling:**
- The application must be fully stateless — no in-process session state, no
  sticky sessions required
- All shared state lives in external stores: PostgreSQL (orders, inventory,
  idempotency keys) and optionally Redis (cache)
- Multiple instances can run concurrently without coordination beyond the
  database

**Database scaling:**
- HikariCP connection pool: minimum 10, maximum 50 connections per instance
- The schema must support 100,000+ orders without query degradation — index
  strategy is the primary mechanism (see `04-database.md`)
- Query performance must not degrade as data grows: `EXPLAIN ANALYZE` gates
  in `07-delivery.md § DEL-4`

---

## NFR-3: Reliability (constraint)

**Data consistency:**
- Zero inventory overselling — this is a hard correctness requirement, not a
  best-effort target (see FR-2 and AR-3 for the enforcement mechanism)
- No lost orders: all order writes must be durable (PostgreSQL with fsync)
- Eventual consistency is acceptable for event notifications (FR-7) but not for
  order or inventory data

**Fault tolerance:**
- If a notification subscriber (`WarehouseListener` etc.) throws, the main
  order pipeline must not fail — isolate subscriber exceptions
- Payment gateway calls must be wrapped in a Resilience4j circuit breaker:
  trip after 5 consecutive failures, half-open after 30 seconds
- Network errors to the payment gateway trigger the retry logic in FR-3

**Transaction guarantees:**
- ACID compliance for all order + inventory writes (same PostgreSQL transaction)
- Inventory reservation rolled back if payment fails (BR-6 path)
- The audit log uses `REQUIRES_NEW` so it commits independently — see AR-4

---

## NFR-4: Maintainability (constraint)

**Code quality gates:**
- Test coverage: domain layer > 80% line, 100% branch on critical paths
- Zero critical SonarQube violations
- Checkstyle: Google Java Style Guide
- JavaDoc required on all `public` interfaces in `application/port/`

**Architecture gates:**
- Domain layer contains zero Spring annotations (`@Component`, `@Service`,
  `@Autowired`, etc.)
- Infrastructure adapters must be swappable without modifying domain or
  application layers — the adapter swap demo (`DEL-1` in `07-delivery.md`)
  verifies this
- SOLID principles: specifically, each adapter implements exactly one port
  interface

---

## TR-3: Project Structure

The full package tree below is the canonical layout. Do not deviate without an ADR.

```
src/main/java/com/example/orderprocessing/
├── domain/                            # Pure business logic — zero framework deps
│   ├── model/
│   │   ├── Order.java
│   │   ├── OrderItem.java
│   │   ├── OrderStatus.java           # Enum — all valid statuses
│   │   ├── Money.java                 # Value object — BigDecimal + currency
│   │   └── Product.java
│   ├── service/
│   │   ├── OrderService.java          # Orchestrates the order pipeline
│   │   ├── InventoryService.java
│   │   └── PaymentService.java
│   ├── exception/
│   │   ├── InsufficientInventoryException.java
│   │   └── PaymentFailedException.java
│   └── event/
│       ├── OrderEvent.java
│       └── OrderEventPublisher.java   # Outgoing port interface
│
├── application/                       # Use-case layer — defines ports
│   ├── port/
│   │   ├── in/                        # Incoming ports (what callers can do)
│   │   │   ├── CreateOrderUseCase.java
│   │   │   ├── QueryOrderUseCase.java
│   │   │   └── CancelOrderUseCase.java
│   │   └── out/                       # Outgoing ports (what the domain needs)
│   │       ├── OrderRepository.java
│   │       ├── ProductRepository.java
│   │       ├── PaymentGateway.java
│   │       └── NotificationService.java
│   └── config/
│       └── UseCaseConfiguration.java  # Wires domain services to ports
│
├── infrastructure/                    # All framework-specific code
│   ├── persistence/
│   │   ├── jpa/
│   │   │   ├── JpaOrderRepository.java
│   │   │   ├── OrderEntity.java       # JPA entity — NOT the domain Order
│   │   │   └── OrderJpaAdapter.java   # Implements OrderRepository port
│   │   └── config/
│   │       └── DatabaseConfiguration.java
│   ├── payment/
│   │   ├── StripePaymentAdapter.java
│   │   ├── PayPalPaymentAdapter.java
│   │   └── MockPaymentGateway.java    # Used in all non-production profiles
│   ├── notification/
│   │   └── EventNotificationAdapter.java
│   ├── rest/
│   │   ├── OrderController.java
│   │   ├── dto/
│   │   │   ├── CreateOrderRequest.java
│   │   │   └── OrderResponse.java
│   │   └── exception/
│   │       └── GlobalExceptionHandler.java
│   └── cache/
│       └── CacheConfiguration.java
└── OrderProcessingApplication.java

src/test/java/
├── domain/          # Pure JUnit 5 — no Spring context loaded
├── application/     # Use-case tests — mock the out-ports
└── infrastructure/  # Integration tests — Testcontainers PostgreSQL
```

**Dependency rule — never violate:**
```
Infrastructure → Application → Domain
```
Domain has no outward arrows. If you find yourself importing a Spring class
inside `domain/`, stop and fix the direction.

---

## AR-1: Hexagonal Architecture (Ports and Adapters)

### Domain layer rules

- Contains pure business logic and domain model only
- Zero dependencies on any framework (`javax.*`, `jakarta.*`, `org.springframework.*`
  are all banned)
- No knowledge of HTTP, JPA, SQL, or message brokers
- All interactions with the outside world happen through port interfaces defined
  in `application/port/`
- Fully testable using plain JUnit 5 — no mocking framework needed for the
  domain in isolation

### Application layer rules

- Defines incoming ports (use case interfaces that controllers call)
- Defines outgoing ports (dependency interfaces that the domain calls)
- Orchestrates domain services and sets transaction boundaries
- `@Transactional` annotations live here — not in the domain

### Infrastructure layer rules

- Implements every port interface
- All Spring annotations (`@Repository`, `@Service`, `@Component`, etc.) are
  confined here
- Database entities (`*Entity`) are separate classes from domain models — use
  mapper classes to convert between them
- Adapters must be replaceable without any change to `domain/` or `application/`

---

## AR-2: Design Pattern Requirements

Five patterns are mandatory. Each requires a dedicated ADR (see `07-delivery.md
§ DOC-1`).

### 1 — Strategy: Payment Processing

```java
// application/port/out/
interface PaymentStrategy {
    PaymentResult process(PaymentRequest request);
}

// infrastructure/payment/
class CreditCardPaymentStrategy implements PaymentStrategy { }
class PayPalPaymentStrategy implements PaymentStrategy  { }
class CryptoPaymentStrategy implements PaymentStrategy  { }
```

Runtime selection: a `PaymentStrategyFactory` resolves the correct strategy
based on `PaymentMethod` enum. This is separate from the `PaymentGateway`
adapter — Strategy handles the *method*, Adapter handles the *provider*.

### 2 — Factory: Order Creation

```java
interface OrderFactory {
    Order createOrder(OrderType type, OrderRequest request);
}
// Produces: StandardOrder, ExpressOrder, SubscriptionOrder
```

Hides the construction complexity of domain `Order` objects. Different order
types carry different validation rules and pricing logic.

### 3 — Observer: Event Notifications

```java
interface OrderEventListener {
    void onOrderStatusChanged(OrderEvent event);
}

class WarehouseListener             implements OrderEventListener { }
class AccountingListener            implements OrderEventListener { }
class CustomerNotificationListener  implements OrderEventListener { }
```

Publisher lives in `domain/event/`. Listeners are registered at startup via
`UseCaseConfiguration`. Dispatch is asynchronous — see FR-7 and [OI-4].

### 4 — Decorator: Order Enhancements

```java
interface OrderEnhancement {
    Order enhance(Order order);
}

class GiftWrappingDecorator      implements OrderEnhancement { }
class InsuranceDecorator         implements OrderEnhancement { }
class PriorityHandlingDecorator  implements OrderEnhancement { }
```

Applied as a chain after `OrderFactory.createOrder()`, before persistence.
Demonstrates the Open/Closed Principle — new enhancements without touching
existing code.

### 5 — Adapter: External Services

```java
// Port in application/port/out/
interface PaymentGateway {
    PaymentResult authorize(PaymentRequest request);
    void capture(String transactionId);
    void refund(String transactionId, Money amount);
}

// Adapters in infrastructure/payment/
class StripePaymentAdapter    implements PaymentGateway { }
class BraintreePaymentAdapter implements PaymentGateway { }
```

The domain never knows which provider is active. Swapping providers is a
configuration change only.

---

## AR-3: Concurrency Architecture

### Thread pool configuration

```java
@Configuration
public class ExecutorConfiguration {

    @Bean("orderProcessingExecutor")
    public ThreadPoolExecutor orderProcessingExecutor() {
        return new ThreadPoolExecutor(
            8,                               // corePoolSize
            16,                              // maximumPoolSize
            60L, TimeUnit.SECONDS,           // keepAliveTime
            new LinkedBlockingQueue<>(1000), // workQueue capacity
            new ThreadPoolExecutor.CallerRunsPolicy() // backpressure on caller
        );
    }

    @Bean("inventoryCheckExecutor")
    public ThreadPoolExecutor inventoryCheckExecutor() {
        return new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new ThreadPoolExecutor.AbortPolicy() // fail fast on overflow
        );
    }
}
```

`CallerRunsPolicy` on the order executor provides implicit backpressure — the
HTTP thread blocks rather than accepting work it cannot process. `AbortPolicy`
on the inventory executor causes an exception that the caller must handle
(retry or fail the order).

### Reactive processing pipeline

```java
public Mono<Order> processOrder(OrderRequest request) {
    return validateOrder(request)
        .flatMap(this::checkInventory)
        .flatMap(this::authorizePayment)
        .flatMap(this::confirmOrder)
        .doOnError(this::handleFailure);
}
```

Non-blocking I/O path: `checkInventory` and `authorizePayment` are the primary
candidates for reactive execution because they both call external systems
(database under load, mock payment gateway).

### Concurrency requirements

- Inventory checks: `CompletableFuture.allOf()` for per-product parallel queries
- Main pipeline: Project Reactor `Mono<Order>` chain for non-blocking I/O
- Backpressure: `CallerRunsPolicy` at the HTTP layer, Reactor's built-in
  backpressure operators inside the pipeline
- Inventory snapshot reads under high concurrency: `ReentrantReadWriteLock`
  — read lock for queries, write lock for reservations

> **[OI-7]** The `CallerRunsPolicy` (this file) and the `RateLimitInterceptor`
> in `05-api.md § API-4` are complementary: rate limiter rejects at the HTTP
> edge; CallerRunsPolicy throttles at the executor boundary. Both must be
> tested under load (see `06-testing.md § TEST-3 Scenario 4`).

---

## AR-4: Transaction Management

### Propagation strategy

```java
@Service
public class OrderService {

    @Transactional(propagation = Propagation.REQUIRED)
    public Order createOrder(OrderRequest request) {
        Order order = orderFactory.createOrder(request);
        orderRepository.save(order);
        inventoryService.reserveInventory(order);  // joins this transaction
        auditService.logOrderCreation(order);       // independent transaction
        return order;
    }
}

@Service
public class InventoryService {

    // Joins the parent transaction — rolls back parent if inventory insufficient
    @Transactional(propagation = Propagation.REQUIRED)
    public void reserveInventory(Order order) { ... }
}

@Service
public class AuditService {

    // Separate transaction — commits even if the parent rolls back
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderCreation(Order order) { ... }
}
```

### Isolation level strategy

| Operation | Isolation level | Reason |
|---|---|---|
| Order creation, status update | `READ_COMMITTED` (default) | Sufficient for non-inventory writes |
| Inventory reservation / deduction | `REPEATABLE_READ` | Prevents phantom reads on stock count |
| Reporting queries | `READ_UNCOMMITTED` | Dirty reads acceptable for analytics |

> **[OI-8]** `REPEATABLE_READ` must be set explicitly on inventory transactions.
> Spring `@Transactional(isolation = Isolation.REPEATABLE_READ)` — it will not
> inherit from a parent `READ_COMMITTED` transaction. Test coverage for this is
> in `04-database.md § DB-3`.

---

## AR-5: Spring Framework Internals

### Custom BeanPostProcessor — audit injection

```java
@Component
public class AuditBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean.getClass().isAnnotationPresent(Auditable.class)) {
            return Proxy.newProxyInstance(
                bean.getClass().getClassLoader(),
                bean.getClass().getInterfaces(),
                new AuditInvocationHandler(bean)
            );
        }
        return bean;
    }
}
```

Applied to any bean annotated `@Auditable`. Wraps it in a JDK dynamic proxy
that records method entry/exit. Lives in `infrastructure/` — the domain never
sees it.

### Custom AOP aspect — retry logic

```java
@Aspect
@Component
public class RetryAspect {

    @Around("@annotation(retryable)")
    public Object retry(ProceedingJoinPoint pjp, Retryable retryable)
            throws Throwable {
        int maxAttempts = retryable.maxAttempts();
        long delayMs    = retryable.delay();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return pjp.proceed();
            } catch (Exception e) {
                if (attempt == maxAttempts) throw e;
                Thread.sleep(delayMs * attempt); // linear backoff shown; use exponential in prod
            }
        }
        throw new IllegalStateException("unreachable");
    }
}

// Usage on a synchronous method
@Service
public class PaymentService {

    @Retryable(maxAttempts = 3, delay = 1000)
    public PaymentResult authorizePayment(PaymentRequest request) {
        return paymentGateway.authorize(request);
    }
}
```

> **[OI-9]** `Thread.sleep()` inside `@Around` advice blocks the carrier thread.
> If `authorizePayment` is called from within the reactive pipeline, this
> defeats the non-blocking model. Resolution: make `RetryAspect` apply only to
> explicitly synchronous service methods and use Resilience4j `RetryOperator`
> or `Mono.retryWhen()` inside the reactive chain. Pick one canonical retry
> path in Phase 2 and document it in an ADR.

### Request-scoped context bean

```java
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST,
       proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RequestContext {
    private String correlationId;
    private String apiKey;
    private Instant requestTimestamp;
    // Populated by a HandlerInterceptor — injected automatically per HTTP request
}
```

Used to propagate the correlation ID into all log entries for a request without
threading it through every method signature. See NFR-5 in `03-performance.md`
for the structured logging requirement.