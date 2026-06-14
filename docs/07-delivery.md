# 07 — Delivery

<!--
MASTER SECTIONS COVERED
  § DOC-1: Architectural Decision Records (ADR list + template)
  § DOC-2: README Documentation (required sections)
  § DOC-3: Performance Report (template)
  § DOC-4: API Documentation (OpenAPI / Swagger config)
  § LO-1..8: Learning Objectives with skill checklists and evidence
  § DEL-1..5: Deliverable checklists

IMPLEMENTATION PHASE
  Phase 1–5 (ongoing) : LO checklists are your per-phase progress tracker;
                        tick items as you implement them, not at the end
  Phase 4 (Wks 13–16) : ADRs for architecture and patterns (DOC-1)
  Phase 5 (Wks 17–20) : Swagger/OpenAPI wired (DOC-4)
  Phase 6 (Wks 21–24) : README finalised (DOC-2), performance report written
                        (DOC-3), all DEL checklists completed

OPEN ISSUES FROM SPLIT REVIEW
  [OI-11] DOC-3 template contains example numbers (10,234 orders/sec, 87ms
          p99). These are illustrative. Replace every number with your actual
          measured results. Do not submit a report with the placeholder values.
  [OI-19] DOC-1 lists 10 ADRs. Several are already partially specified in this
          requirements doc. Write the full ADR text, including consequences and
          trade-offs, during the phase in which the decision is first
          implemented — not as a batch at the end.
-->

---

## DOC-1: Architectural Decision Records

Minimum 10 ADRs, all written in English, stored in `docs/adr/`.

### ADR index

| ADR | Title | Primary file reference | Decision phase |
|---|---|---|---|
| ADR-001 | Database Selection — PostgreSQL vs MongoDB | `04-database.md` | Phase 1 |
| ADR-002 | Concurrency Model — Reactive vs Thread-per-Request | `02-architecture.md § AR-3` | Phase 2 |
| ADR-003 | Inventory Locking — Optimistic vs Pessimistic | `04-database.md § DB-2` | Phase 2 |
| ADR-004 | Architecture Style — Hexagonal (Ports and Adapters) | `02-architecture.md § AR-1` | Phase 4 |
| ADR-005 | Transaction Propagation Strategy | `02-architecture.md § AR-4` | Phase 4 |
| ADR-006 | Caching Layer — Caffeine vs Redis | `03-performance.md § PER-4` | Phase 3 |
| ADR-007 | Payment Gateway Abstraction — Strategy + Adapter | `02-architecture.md § AR-2` | Phase 4 |
| ADR-008 | API Versioning Strategy — URL Versioning | `05-api.md § API-3` | Phase 5 |
| ADR-009 | Error Response Format — RFC 7807 | `05-api.md § API-2` | Phase 5 |
| ADR-010 | GC Algorithm Selection — G1GC vs ZGC | `03-performance.md § PER-1` | Phase 3 |

### ADR template

```markdown
# ADR-XXX: [Title]

## Status
[Proposed | Accepted | Deprecated | Superseded by ADR-YYY]

## Context
What problem are we solving? What constraints exist?
What happens if we do nothing?

## Decision
What have we decided to do?

## Rationale
Why this option over the alternatives considered?
List at least two alternatives with their trade-offs.

## Consequences
What becomes easier? What becomes harder?
What new risks does this introduce?

## Implementation Notes
Key code examples or configuration snippets. Link to the relevant
requirements file section.

## Related ADRs
[Links to decisions that depend on or conflict with this one]
```

---

## DOC-2: README Documentation

The `README.md` at repository root must include all of the following sections.

```markdown
# High-Performance Order Processing System

## Overview
One paragraph: what the system does, its throughput and latency targets,
and the primary learning goals.

## Architecture
- Architecture diagram (draw a hexagonal diagram — ASCII or an image)
- Layer responsibilities (domain / application / infrastructure)
- Key design patterns used and where they live

## Technology Stack
Table of: Java 17, Spring Boot 3.x, PostgreSQL 15, Project Reactor,
Resilience4j, Micrometer, Testcontainers, JMeter.

## Prerequisites
- JDK 17+
- Docker Desktop
- Maven 3.8+

## Quick Start
```bash
docker-compose up -d          # PostgreSQL + Redis
mvn spring-boot:run           # Application on :8080
mvn test                      # Unit + integration tests
mvn test -P performance       # JMeter load tests (Scenario 1)
./scripts/run-load-test.sh    # Full JMeter suite
```

## API Documentation
Swagger UI: http://localhost:8080/swagger-ui.html

## Performance Benchmarks
- Throughput: [fill with measured result] orders/sec sustained
- Latency: p99 [fill with measured result] ms
- See docs/PERFORMANCE.md for full report

## Architecture Decisions
See docs/adr/ for all 10+ ADRs

## Project Structure
src/
├── domain/          # Business logic — zero framework dependencies
├── application/     # Use-case interfaces and ports
└── infrastructure/  # Spring, JPA, REST, payment adapters

## Development
- Style: Google Java Style Guide
- Branching: feature/*, bugfix/*
- Coverage gate: >80% for domain layer

## Monitoring
- Metrics:  http://localhost:8080/actuator/prometheus
- Health:   http://localhost:8080/actuator/health

## License
MIT
```

---

## DOC-3: Performance Report Template

Store the completed report at `docs/PERFORMANCE.md`.

> **[OI-11]** Replace every number below with your actual measured values.
> The numbers shown are illustrative targets from the requirements, not
> pre-filled results.

```markdown
# Performance Benchmark Report

## Test Environment
- Date: [fill]
- Hardware: [CPU cores, RAM]
- JVM: OpenJDK [version]
- JVM Flags: -Xms512m -Xmx2g -XX:+UseG1GC [+ tuning flags]
- Database: PostgreSQL 15, shared_buffers=[fill]

## Test Scenarios

### Scenario 1: Baseline (1000 orders/sec × 10 min)
- Throughput: [fill] orders/sec
- Error Rate: [fill]%

### Scenario 2: Peak Load (10,000 orders/sec × 5 min)
- Throughput: [fill] orders/sec
- Error Rate: [fill]%

## Latency Distribution

| Percentile | Latency (ms) | Target (ms) | Pass? |
|------------|--------------|-------------|-------|
| p50        | [fill]       | < 30        | [✅/❌] |
| p95        | [fill]       | < 75        | [✅/❌] |
| p99        | [fill]       | < 100       | [✅/❌] |
| p99.9      | [fill]       | < 250       | [✅/❌] |

## JVM Performance

### GC (G1GC baseline)
- Pause time p99: [fill] ms (target: < 10 ms)
- GC overhead: [fill]% of total time

### GC (ZGC comparison)
- Pause time p99: [fill] ms
- Throughput delta vs G1GC: [fill]%

### Memory
- Peak heap: [fill] GB of [fill] GB max
- 72-hour leak test: [PASS / FAIL]

### Thread pool
- Peak active threads: [fill] of [fill] max
- Max queue depth: [fill] of [fill] capacity

## Database
- Average query time: [fill] ms
- Slowest query: [fill] ms ([query description])
- Index hit rate: [fill]%
- Pool peak active: [fill] of [fill]

## Synchronous vs Reactive Comparison

| Metric | Synchronous | Reactive | Delta |
|--------|-------------|----------|-------|
| Max throughput | [fill] | [fill] | [fill]% |
| Thread count | [fill] | [fill] | [fill]% |
| Heap under load | [fill] GB | [fill] GB | [fill]% |
| p99 latency | [fill] ms | [fill] ms | [fill]% |

## Optimisation Impact

### Before
- p99: [fill] ms, GC pause: [fill] ms, throughput: [fill] orders/sec

### After
- p99: [fill] ms, GC pause: [fill] ms, throughput: [fill] orders/sec

### Key changes
1. [Optimisation 1] → [impact]
2. [Optimisation 2] → [impact]

## CPU Hotspots (top 5 from profiler)
1. [class.method()] — [fill]% CPU
2. ...

## Recommendations
[What would you do next with more time?]
```

---

## DOC-4: API Documentation (OpenAPI/Swagger)

Swagger configuration is in `05-api.md § NFR-6`. This section defines the
completeness requirement.

Every public endpoint must have:
- `@Operation(summary, description)`
- `@ApiResponse` for each possible HTTP status (200/201, 400, 401, 404,
  409, 422, 429, 500 where applicable)
- `@Parameter` on all path variables and required headers
- `@Schema` on all request/response DTOs

Swagger UI must be accessible at `http://localhost:8080/swagger-ui.html`
when running with `dev` or `local` Spring profile.

---

## Learning Objectives

### LO-1: JVM & Memory Management
_Linked to: PER-1 (`03-performance.md`), Phase 3_

**Skills checklist:**
- [ ] Configure and tune G1GC — document flags and MaxGCPauseMillis rationale
- [ ] Profile heap allocation under load using JProfiler or VisualVM
- [ ] Identify a real or induced memory leak using heap dump + JProfiler Heap Walker
- [ ] Compare G1GC vs ZGC pause time distribution under load
- [ ] Explain heap vs stack allocation with a code example from this project
- [ ] Reduce object allocation pressure — identify a hotspot and fix it

**Evidence required (DEL-4):**
- Performance report with GC before/after comparison
- Heap dump analysis document
- Allocation flamegraph from async-profiler

---

### LO-2: Concurrency & Multithreading
_Linked to: AR-3 (`02-architecture.md`), PER-2 (`03-performance.md`), Phase 2_

**Skills checklist:**
- [ ] ThreadPoolExecutor with custom rejection policy — explain CallerRunsPolicy choice
- [ ] CompletableFuture.allOf() for parallel inventory checks (FR-2)
- [ ] ReentrantReadWriteLock on inventory reads — measured improvement documented
- [ ] Project Reactor reactive pipeline processing the order flow
- [ ] CountDownLatch-based concurrency test that proves no overselling (TEST-4)
- [ ] Explain the Java Memory Model (JMM) visibility guarantee for the version field

**Evidence required:**
- Working reactive pipeline code
- Concurrency test passing (TEST-4 assertions hold)
- Sync vs reactive comparison table in performance report

---

### LO-3: Spring Framework Deep Dive
_Linked to: AR-5 (`02-architecture.md`), Phase 4_

**Skills checklist:**
- [ ] Custom `BeanPostProcessor` — audit injection via JDK dynamic proxy
- [ ] Custom `@Aspect` with `@Around` — retry logic (resolve [OI-9] first)
- [ ] Transaction propagation: demonstrate REQUIRED vs REQUIRES_NEW with tests
- [ ] Bean lifecycle: explain `@PostConstruct`, `@PreDestroy`, and scope proxy
- [ ] Request-scoped `RequestContext` bean injecting correlation ID into logs

**Evidence required:**
- ADR-005 documenting the transaction propagation strategy
- DB-3 isolation tests passing
- AuditBeanPostProcessor wired and logging method calls

---

### LO-4: Database Optimisation
_Linked to: DB-1..3 (`04-database.md`), PER-3 (`03-performance.md`), Phase 3_

**Skills checklist:**
- [ ] Design the composite index `(customer_id, status, created_at DESC)` —
  explain why column order matters for this query pattern
- [ ] Run EXPLAIN ANALYZE on every hot-path query at 100k rows
- [ ] Implement optimistic locking with `@Version` — race condition test passes
- [ ] Test all three isolation levels with Testcontainers (DB-3 scenarios)
- [ ] Tune HikariCP pool size — justify min/max with load test data
- [ ] Identify and fix at least one N+1 query (order items eager vs lazy loading)

**Evidence required:**
- EXPLAIN ANALYZE output for top 10 queries in performance report
- Race condition test (TEST-4) consistently passing
- HikariCP metrics in performance report

---

### LO-5: Clean Architecture
_Linked to: AR-1 (`02-architecture.md`), TR-3, Phase 4_

**Skills checklist:**
- [ ] Full hexagonal structure in place — verify with a package dependency check tool
- [ ] Domain layer builds and tests pass with no Spring jars on the compile classpath
- [ ] At least one outgoing port has two adapter implementations (e.g. JPA + in-memory)
- [ ] Swap the persistence adapter: demonstrate running integration tests against both
- [ ] All domain unit tests run without `@SpringBootTest`

**Evidence required:**
- DEL-1: GitHub repository with the correct package structure
- Demonstrated adapter swap (even just in a test profile)

---

### LO-6: Design Patterns
_Linked to: AR-2 (`02-architecture.md`), Phase 4_

**Skills checklist:**
- [ ] Strategy pattern — PaymentStrategy with 3 implementations, runtime selection
- [ ] Factory pattern — OrderFactory producing StandardOrder, ExpressOrder
- [ ] Observer pattern — 3 listeners receiving OrderEvent asynchronously
- [ ] Decorator pattern — at least 2 OrderEnhancement decorators chained
- [ ] Adapter pattern — at least 2 PaymentGateway adapter implementations

**Evidence required:**
- ADR for each pattern (can be part of ADR-007 or individual ADRs)
- Working code with tests demonstrating extensibility for each pattern

---

### LO-7: API Design
_Linked to: API-1..4, NFR-6 (`05-api.md`), Phase 5_

**Skills checklist:**
- [ ] Idempotent POST /orders — TEST-5 passing, 201 → 200 on replay
- [ ] /v1 and /v2 controllers with documented breaking changes
- [ ] RFC 7807 error responses from GlobalExceptionHandler for all error types
- [ ] Rate limiting interceptor with response headers on every request
- [ ] Full OpenAPI/Swagger UI live at /swagger-ui.html (DOC-4 completeness)

**Evidence required:**
- TEST-5 idempotency tests passing
- Screenshot or log evidence of v1 deprecation warning header
- Swagger UI with all operations documented

---

### LO-8: Technical Writing (English)
_Linked to: DOC-1..4, Phase 6_

**Skills checklist:**
- [ ] 10+ ADRs written in clear technical English — no machine translation artefacts
- [ ] README covers all sections in DOC-2
- [ ] Performance report uses the DOC-3 template with real measured values
- [ ] API documentation complete per DOC-4
- [ ] Trade-offs explained in ADRs — not just "we chose X" but "we chose X over Y
  because Z, accepting the trade-off that W"

**Evidence required:**
- `docs/adr/` folder with 10+ `.md` files
- `docs/PERFORMANCE.md` with real data
- README architecture section with a diagram

---

## Deliverables

### DEL-1: Source Code
- [ ] GitHub repository, `main` branch, clean commit history
- [ ] Hexagonal package structure as specified in TR-3
- [ ] All identifiers (classes, methods, variables, comments) in English
- [ ] Google Java Style Guide enforced — Checkstyle passing in CI
- [ ] Zero compiler warnings in a clean build (`mvn clean verify`)

### DEL-2: Documentation
- [ ] `README.md` covering all DOC-2 sections
- [ ] `docs/adr/ADR-001.md` through `ADR-010.md` (minimum)
- [ ] `docs/PERFORMANCE.md` using DOC-3 template with real data
- [ ] Swagger UI live — all endpoints documented per DOC-4
- [ ] `docs/schema.md` or equivalent covering DB-1 schema decisions

### DEL-3: Test Suite
- [ ] Domain unit tests: > 80% line coverage, no Spring context
- [ ] Integration tests: Testcontainers PostgreSQL, all FR scenarios covered
- [ ] Concurrency tests: TEST-4 passing consistently (run 10×, all pass)
- [ ] Idempotency tests: TEST-5 passing
- [ ] Performance tests: JMeter scripts in `load-tests/` directory
- [ ] All tests passing in CI (GitHub Actions or equivalent)

### DEL-4: Performance Evidence
- [ ] JMeter results: all 4 scenarios (CSV + HTML report)
- [ ] JProfiler screenshots: heap summary, GC pause histogram, thread pool usage
- [ ] GC tuning: before/after comparison (G1GC default → tuned → ZGC)
- [ ] Latency distribution chart: p50/p95/p99/p99.9 over a 10-minute test
- [ ] Sync vs reactive comparison table with measured numbers

### DEL-5: Deployment Artifacts
- [ ] `Dockerfile` — multi-stage build, JVM flags in `ENTRYPOINT`
- [ ] `docker-compose.yml` — app + PostgreSQL + Redis with health checks
- [ ] Spring profiles: `local`, `test`, `prod` with appropriate config
- [ ] Flyway migration scripts in `src/main/resources/db/migration/`
- [ ] `/actuator/health/liveness` and `/actuator/health/readiness` responding correctly