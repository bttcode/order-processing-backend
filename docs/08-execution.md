# 08 — Execution

<!--
MASTER SECTIONS COVERED
  § Implementation Phases 1–6 (goals, deliverables, learning focus per phase)
  § Bonus Challenges (optional advanced work)
  § Resources (books, courses, tools, documentation)
  § Evaluation Checklist (Must Have / Should Have / Could Have)

IMPLEMENTATION PHASE
  This file IS the phase plan — re-read at the start of each phase.
  The evaluation checklist at the bottom is the final self-assessment gate
  before marking the project complete.

OPEN ISSUES FROM SPLIT REVIEW
  [OI-20] The phase plan puts performance profiling in Phase 3 (Weeks 9–12)
          but the concurrency layer (Phase 2) is not complete until Week 8.
          Phase 3 depends on Phase 2 being stable. If Phase 2 slips,
          Phase 3 start must also slip — do not start JVM profiling on a
          concurrency implementation that is still changing.
  [OI-21] Phase 4 (architecture refactor, Weeks 13–16) is listed after
          performance work. This is intentional — profile the working
          system before refactoring, so you have a baseline. After the
          hexagonal refactor, re-run the performance tests to confirm
          the architecture change did not regress throughput.
  [OI-22] Bonus challenges (CQRS, Saga, event sourcing) are significant
          scope additions. Only attempt them if Phases 1–6 are complete
          and all DEL checklists in 07-delivery.md are ticked. Do not
          let bonus work displace the core deliverables.
-->

---

## Phase 1 — Foundation (Weeks 1–4)

**Goal:** A working order creation endpoint backed by a real PostgreSQL schema.
No concurrency, no reactive pipeline, no hexagonal structure yet — those come
later. Get something that creates orders, persists them, and has a test.

### Phase 1 deliverables
- [ ] Spring Boot project scaffolded with Maven/Gradle
- [ ] PostgreSQL schema created via Flyway migration (all tables in `04-database.md § DB-1`)
- [ ] `POST /orders` endpoint working synchronously — status reaches CONFIRMED
- [ ] `GET /orders/{id}` returning full order details
- [ ] Basic unit tests for `Order`, `Money`, `OrderStatus` (TEST-1 domain tests)
- [ ] `docker-compose.yml` starting PostgreSQL and the application

### Phase 1 learning focus
- Spring Data JPA basics: entity mapping, repository, `@Transactional`
- Flyway migration workflow
- Basic REST controller + `@Valid` bean validation
- Domain model design: `Order`, `OrderItem`, `Money` value object

---

## Phase 2 — Concurrency Layer (Weeks 5–8)

**Goal:** Make the inventory check and order pipeline concurrent. Prove no
overselling. Implement the reactive pipeline. Get TEST-4 passing.

### Phase 2 deliverables
- [ ] `CompletableFuture.allOf()` in inventory check (FR-2 parallel queries)
- [ ] `@Version` optimistic locking on `ProductEntity` (DB-2)
- [ ] Retry on `OptimisticLockException` (max 3 attempts)
- [ ] Thread pool executors configured (`02-architecture.md § AR-3`)
- [ ] Reactive `Mono<Order>` pipeline wired end-to-end
- [ ] TEST-4 concurrency test passing consistently

### Phase 2 learning focus
- `java.util.concurrent`: `ExecutorService`, `CompletableFuture`, `CountDownLatch`
- Project Reactor: `Mono`, `flatMap`, `doOnError`, backpressure
- Optimistic locking and version conflicts
- Java Memory Model: visibility of `version` field across threads

---

## Phase 3 — Performance Optimisation (Weeks 9–12)

**Goal:** Profile the system under load, tune the JVM and database, and produce
the performance evidence for DEL-4. Do not start until Phase 2 is stable.

> **[OI-20]** Phase 3 depends on Phase 2 being stable. Verify TEST-4 passes
> reliably before running JMeter loads — flaky concurrency will produce
> misleading profiling data.

### Phase 3 deliverables
- [ ] JMeter scripts for all 4 scenarios (`06-testing.md § TEST-3`)
- [ ] Scenario 1 and Scenario 2 results recorded
- [ ] G1GC baseline profile: heap flamegraph, GC pause histogram
- [ ] G1GC tuning applied and re-measured
- [ ] ZGC comparison run and documented
- [ ] ADR-010 written (GC selection)
- [ ] EXPLAIN ANALYZE for every hot-path query (DB-3 requirement)
- [ ] Caffeine L1 cache wired for product lookups (PER-4)
- [ ] HikariCP metrics instrumented (NFR-5)
- [ ] 72-hour endurance test started (Scenario 3) — results in Phase 6

### Phase 3 learning focus
- JVM internals: young/old generation, G1 region collection, ZGC concurrency
- async-profiler and VisualVM under load
- PostgreSQL EXPLAIN ANALYZE: cost model, index selection, bitmap scans
- Caffeine cache hit rate measurement via Micrometer

---

## Phase 4 — Architecture Refactor (Weeks 13–16)

**Goal:** Refactor the working (but likely framework-tangled) codebase into
the hexagonal structure. Implement all 5 design patterns. Prove the domain layer
is framework-free.

> **[OI-21]** Re-run the Phase 3 performance tests after this refactor to
> confirm throughput has not regressed. Any regression must be explained
> before Phase 5 starts.

### Phase 4 deliverables
- [ ] Full hexagonal package structure (`02-architecture.md § TR-3`)
- [ ] Domain layer compiles with no Spring jars on classpath
- [ ] All 5 design patterns implemented (AR-2)
- [ ] Domain unit tests running without `@SpringBootTest` (LO-5)
- [ ] Adapter swap demo: JPA adapter swappable with in-memory adapter in test profile
- [ ] FR-6 (cancellation) and FR-7 (events) implemented now that domain is clean
- [ ] ADR-004 (architecture style) written
- [ ] ADR-007 (payment gateway abstraction) written

### Phase 4 learning focus
- Dependency inversion: ports as interfaces, adapters as implementations
- SOLID principles applied at the boundary between layers
- JDK dynamic proxies (used in AR-5 BeanPostProcessor)
- Observer pattern async dispatch without coupling to Spring events

---

## Phase 5 — API Design & Integration (Weeks 17–20)

**Goal:** Complete the API surface — idempotency, versioning, rate limiting,
RFC 7807 errors, and full Swagger documentation.

### Phase 5 deliverables
- [ ] Idempotency-Key handling for POST /orders and cancel (FR-8, TEST-5)
- [ ] `idempotency_keys` cleanup job (see [OI-6] from `01-functional.md`)
- [ ] `/api/v1/` and `/api/v2/` controllers with documented breaking changes
- [ ] RFC 7807 error responses from `GlobalExceptionHandler` for all error types
- [ ] `RateLimitInterceptor` with response headers ([OI-15]: document global vs per-key trade-off)
- [ ] `X-API-Key` validation interceptor
- [ ] ADR-008 (versioning), ADR-009 (RFC 7807) written
- [ ] Swagger UI live with all endpoints documented (DOC-4)
- [ ] TEST-5 idempotency tests passing

### Phase 5 learning focus
- Idempotency key patterns: store-and-replay vs natural idempotency
- API versioning trade-offs: URL vs header vs content-type negotiation
- Spring `HandlerInterceptor` execution order
- `@RestControllerAdvice` and `ProblemDetail` (Spring 6 built-in)

---

## Phase 6 — Polish & Documentation (Weeks 21–24)

**Goal:** Complete all documentation, run the final performance test suite,
clean up the codebase, and produce the evidence package for the evaluation
checklist.

### Phase 6 deliverables
- [ ] All 10+ ADRs written in full (not just titles and one-liners)
- [ ] README completed per DOC-2 template (with architecture diagram)
- [ ] `docs/PERFORMANCE.md` completed per DOC-3 template with real data
- [ ] 72-hour endurance test results recorded (started in Phase 3)
- [ ] Code review pass: zero Checkstyle violations, zero compiler warnings
- [ ] CI pipeline running all test categories (unit, integration, concurrency)
- [ ] Evaluation checklist below fully completed

### Phase 6 learning focus
- Technical writing: precise trade-off language, evidence-based claims
- Documentation as a first-class deliverable, not an afterthought

---

## Bonus Challenges (Optional)

Attempt only after Phases 1–6 are fully complete and all DEL checklists in
`07-delivery.md` are ticked.

> **[OI-22]** These are significant scope expansions. Each could take 2–4 weeks.
> Do not let them displace core deliverables.

### Advanced Performance
- [ ] ZGC deep-dive: production flame graph via async-profiler `wall` mode
- [ ] Redis L2 distributed cache with cache-aside pattern (PER-4)
- [ ] PostgreSQL read replica for order history queries
- [ ] async-profiler `lock` profiling to find hidden contention

### Advanced Architecture
- [ ] Event sourcing: replace status column with an event log; rebuild current
      state by replaying events
- [ ] CQRS: separate read model (order summaries) from write model (order commands)
- [ ] Saga pattern: distributed order-payment-inventory transaction with
      compensating actions
- [ ] Resilience4j circuit breaker around the payment gateway adapter (NFR-3)

### Advanced DevOps
- [ ] Kubernetes deployment manifests with liveness/readiness probes
- [ ] Terraform module for PostgreSQL + Redis on a cloud provider
- [ ] GitHub Actions CI/CD: build → test → Docker push on merge to main
- [ ] Prometheus + Grafana dashboard for the metrics in NFR-5

---

## Resources

### Books
| Title | Author | Relevance |
|---|---|---|
| Effective Java (3rd ed.) | Joshua Bloch | LO-2, LO-5, LO-6 |
| Java Concurrency in Practice | Brian Goetz | LO-2, AR-3 |
| Clean Architecture | Robert C. Martin | LO-5, AR-1 |
| Designing Data-Intensive Applications | Martin Kleppmann | DB-1..3, NFR-3 |

### Online courses
- Spring Framework Deep Dive — Pluralsight (LO-3)
- Java Performance Tuning — LinkedIn Learning (LO-1, PER-1)
- Reactive Programming with Project Reactor — Udemy (LO-2, AR-3)

### Tools
| Tool | URL |
|---|---|
| JProfiler | https://www.ej-technologies.com/products/jprofiler/overview.html |
| VisualVM | https://visualvm.github.io/ |
| async-profiler | https://github.com/async-profiler/async-profiler |
| JMeter | https://jmeter.apache.org/ |
| Testcontainers | https://www.testcontainers.org/ |

### Documentation
| Reference | URL |
|---|---|
| Spring Framework 6.x | https://docs.spring.io/spring-framework/reference/ |
| Project Reactor | https://projectreactor.io/docs/core/release/reference/ |
| PostgreSQL performance tips | https://www.postgresql.org/docs/current/performance-tips.html |
| Resilience4j | https://resilience4j.readme.io/ |
| Micrometer | https://micrometer.io/docs |

---

## Evaluation Checklist

Self-assessment gate before marking the project complete. Be honest — this is
for your own learning audit.

### Core Requirements (Must Have)

- [ ] System sustains 10,000 orders/sec for 10 minutes (Scenario 2 result)
- [ ] p99 latency < 100 ms (measured, not estimated)
- [ ] Zero inventory overselling — TEST-4 passes 10× in a row
- [ ] Hexagonal architecture: domain layer has zero Spring annotations
- [ ] All 5 design patterns implemented and testable in isolation
- [ ] 10+ ADRs written in clear English — each with context, decision,
      rationale, consequences
- [ ] Domain layer test coverage > 80%
- [ ] Performance report (DOC-3) completed with real measured data

### Technical Depth (Should Have)

- [ ] GC tuning documented: before/after numbers with explanation
- [ ] Reactive pipeline vs synchronous comparison table with measurements
- [ ] Optimistic locking: TEST-4 proves it works, DB-3 proves isolation level
      is actually enforced
- [ ] Custom `BeanPostProcessor` and `@Aspect` both wired and observable
- [ ] Transaction propagation: REQUIRED vs REQUIRES_NEW demonstrated with
      a failing test that proves independence (audit log commits on parent rollback)
- [ ] EXPLAIN ANALYZE output for top-5 queries documented
- [ ] TEST-5 idempotency tests passing
- [ ] JMeter load tests running from scripts (repeatable, not just once)

### Professional Quality (Could Have)

- [ ] Checkstyle passing — zero violations on `mvn checkstyle:check`
- [ ] Comprehensive README with architecture diagram
- [ ] Swagger UI with every endpoint documented
- [ ] `docker-compose.yml` starts everything with one command
- [ ] GitHub Actions CI running on every push
- [ ] Prometheus metrics endpoint live and scraped by a local Grafana instance
- [ ] Repository has a clean linear commit history (squash or rebase merges)
