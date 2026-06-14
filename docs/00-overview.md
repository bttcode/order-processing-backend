# 00 — Overview

<!--
MASTER SECTIONS COVERED
  § Project Overview (Purpose, Scope, Out of Scope)
  § Business Context (Domain, Business Rules, Key Metrics)
  § TR-1: Technology Stack
  § TR-2: Development Environment

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4): read this file before writing a single line of code.
  Re-read before Phase 5 (API) and Phase 6 (docs) to verify scope has not drifted.

OPEN ISSUES FROM SPLIT REVIEW
  [OI-1] NFR-2 (Scalability), NFR-3 (Reliability), NFR-4 (Maintainability) were
         not assigned a home in the original split plan. They now live in
         02-architecture.md. Cross-references added below where relevant.
  [OI-2] TR-1 and TR-2 had no home in the original split plan. Placed here.
  [OI-3] "Out of Scope" silently implies auth is mocked. FR-8 (Idempotency) and
         API-4 (Rate Limiting) both touch auth headers — ensure the simple
         X-API-Key approach in NFR-6 is agreed before Phase 5 starts.
-->

---

## Purpose

Build a production-grade order processing system capable of sustaining **10,000+ orders/second** with **sub-100ms p99 latency**. The primary goal is not delivery velocity — it is mastery of JVM internals, concurrency, Spring Framework internals, clean architecture, and design patterns. All implementation choices must be explainable and evidenced.

**Project metadata**

| Field | Value |
|---|---|
| Version | 1.0 |
| Roadmap phase | Solution Architect Roadmap — Phase 1 (Months 1–18) |
| Duration | 6 months (24 weeks) |
| Primary goal | Code-level optimization, architecture patterns, system internals |

---

## Scope

What this system covers:

- Order creation, validation, and lifecycle management
- Concurrent inventory management with race condition handling
- Payment processing integration — mock multiple providers
- Real-time order status notifications
- Performance monitoring and optimization
- Clean architectural separation with hexagonal design

### Out of scope

| Excluded | Substitute |
|---|---|
| User authentication & authorization | Simple `X-API-Key` header — see NFR-6 in `05-api.md` |
| Product catalog management | Seed a fixed dataset at startup |
| Shopping cart | Not modelled |
| Customer reviews, ratings, recommendations | Not modelled |
| Frontend UI | API-only system |

> **[OI-3]** The X-API-Key approach is intentionally lightweight. Confirm this is
> acceptable before implementing rate limiting in Phase 5, since the current
> `API-4` implementation keys the rate limiter on the API key value — if key
> validation is ever added later it will require refactoring the interceptor.

---

## Business Context

**Domain:** E-commerce order fulfilment for a high-volume online retailer.

### Business rules

These are hard invariants. Every architectural and implementation decision must
be traceable to at least one of them.

| # | Rule |
|---|---|
| BR-1 | Orders must be validated for inventory availability before acceptance |
| BR-2 | Payment authorization must succeed before order confirmation |
| BR-3 | Inventory must be decremented atomically — no overselling |
| BR-4 | Order status changes must trigger downstream notifications |
| BR-5 | All mutating operations must be idempotent (safe to retry) |
| BR-6 | Failed payments must release reserved inventory within 5 minutes |
| BR-7 | Order modifications are not allowed after payment confirmation |

### Key business metrics (targets)

| Metric | Target |
|---|---|
| Order acceptance rate | > 99% |
| Order processing time (p99) | < 100 ms |
| Payment success rate | > 95% |
| Inventory accuracy | 100% — zero overselling |
| System uptime | 99.9% |

---

## Technology Stack (TR-1)

### Core framework

| Component | Choice |
|---|---|
| Language | Java 17 or 21 (LTS) |
| Framework | Spring Boot 3.x / Spring Framework 6.x |
| Build | Maven or Gradle |

### Key libraries

| Concern | Library |
|---|---|
| Concurrency | `java.util.concurrent`, Project Reactor 3.x |
| Persistence | Spring Data JPA, Hibernate 6.x |
| Database (primary) | PostgreSQL 15+ |
| Database (test) | H2 |
| Connection pool | HikariCP |
| Caching L1 | Caffeine |
| Caching L2 | Redis (optional distributed) |
| Validation | Hibernate Validator (Bean Validation 3.0 / JSR-380) |
| Resilience | Resilience4j — circuit breaker, retry, rate limiter |
| Metrics | Micrometer + Spring Boot Actuator |
| Testing | JUnit 5, Mockito, Testcontainers |
| Load testing | JMeter or Gatling |

### Profiling tools

| Tool | Use case |
|---|---|
| JProfiler / YourKit | Heap + CPU profiling under load (commercial) |
| VisualVM | Free alternative to JProfiler |
| async-profiler | Low-overhead production profiling via flame graphs |

---

## Development Environment (TR-2)

### Required setup

- JDK 17+ installed and `JAVA_HOME` set
- Docker Desktop — runs local PostgreSQL and Redis via `docker-compose`
- IntelliJ IDEA or Eclipse, IDE language set to English
- Git for version control
- Postman or `curl` for manual API testing

### JVM launch flags (IDE run config and `docker-compose`)

```
-XX:+UseG1GC
-Xms512m
-Xmx2g
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/order-processing-heap.hprof
```

These are baseline flags. G1GC tuning and ZGC comparison are covered in
`03-performance.md § PER-1`.

---

## Cross-references

| Topic | File |
|---|---|
| All functional use cases (FR-1..8) | `01-functional.md` |
| Hexagonal structure, patterns, concurrency, Spring internals | `02-architecture.md` |
| NFR-2 Scalability, NFR-3 Reliability, NFR-4 Maintainability | `02-architecture.md` |
| JVM/GC tuning, SLAs, caching, observability | `03-performance.md` |
| Database schema, optimistic locking, isolation | `04-database.md` |
| API endpoints, error format, versioning, rate limiting, security | `05-api.md` |
| All test specs | `06-testing.md` |
| Deliverables, ADR list, learning objectives | `07-delivery.md` |
| Phase plan, bonus challenges, resources, evaluation checklist | `08-execution.md` |