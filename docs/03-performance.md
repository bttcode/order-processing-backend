# 03 — Performance

<!--
MASTER SECTIONS COVERED
  § NFR-1: Performance SLAs (throughput, latency, resource utilisation)
  § PER-1: JVM Memory Management (heap config, GC, leak detection)
  § PER-2: Concurrency Optimisation (thread pool tuning, lock contention)
  § PER-3: Database Performance (query targets, index rationale — DDL in 04-database.md)
  § PER-4: Caching Strategy (Caffeine L1, Redis L2, invalidation)
  § NFR-5: Observability (logging, metrics, health checks)

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4)  : NFR-5 baseline — structured logging + health endpoints
                         from day one; correlation ID in every log line
  Phase 2 (Weeks 5–8)  : PER-2 thread pool tuning after concurrency layer lands
  Phase 3 (Weeks 9–12) : PER-1 JVM profiling, GC tuning, leak detection,
                         load test runs, PER-3 query + index analysis,
                         PER-4 Caffeine cache wired in
  Phase 6 (Weeks 21–24): Final performance report produced using DOC-3 template
                         in 07-delivery.md

OPEN ISSUES FROM SPLIT REVIEW
  [OI-1]  NFR-5 (Observability) had no home in the original split plan.
          Placed here because metrics and logging are primarily performance
          instrumentation tools in this project.
  [OI-10] PER-3 in the master document contains duplicate index SQL that also
          appears in DB-1 (04-database.md). All DDL is the single source of
          truth in 04-database.md. This file contains only performance
          rationale and targets — no CREATE INDEX statements.
  [OI-11] The DOC-3 Performance Report template (in 07-delivery.md) contains
          example numbers (10,234 orders/sec, p99 87ms) that look like actual
          results. They are illustrative targets only. Fill the template with
          real measured values during Phase 3 and 6.
-->

---

## NFR-1: Performance SLAs

These are acceptance gates. The system must sustain these numbers to pass the
project evaluation checklist (`08-execution.md § Evaluation`).

### Throughput

| Condition | Target |
|---|---|
| Sustained normal load | 10,000 orders/sec |
| Peak burst (up to 5 minutes) | 15,000 orders/sec |
| Beyond peak | Graceful degradation — return `503 Service Unavailable`, never crash |

### Latency (order creation end-to-end)

| Percentile | Target |
|---|---|
| p50 | < 30 ms |
| p95 | < 75 ms |
| p99 | < 100 ms |
| p99.9 | < 250 ms |

### Resource utilisation

| Resource | Target |
|---|---|
| CPU average | < 70% |
| CPU peak | < 90% |
| Heap | Stable — no linear growth over 72-hour test |
| GC pause time p99 | < 10 ms |

---

## PER-1: JVM Memory Management

### Heap configuration (baseline)

```
-Xms512m                        # initial heap — avoids early GC storms
-Xmx2g                          # maximum heap
-XX:NewSize=512m                 # young generation starting size
-XX:MaxMetaspaceSize=256m        # cap metaspace growth
```

### Garbage collector configuration (baseline — G1GC)

```
-XX:+UseG1GC
-XX:G1HeapRegionSize=4m          # tune region size for ~512 regions at 2g heap
-XX:MaxGCPauseMillis=50          # target max pause; G1 will try to honour this
```

### Performance tasks (Phase 3 ordered sequence)

1. Run baseline load test (1000 orders/sec for 10 min) with G1GC defaults —
   record throughput, p99 latency, GC pause histogram
2. Profile heap allocation under load using VisualVM or JProfiler —
   identify top allocation classes
3. Tune G1GC: adjust `MaxGCPauseMillis`, `G1HeapRegionSize`, young/old ratio
   based on the allocation profile
4. Re-run load test — document improvement in the performance report
5. Switch to ZGC (`-XX:+UseZGC`) — repeat the load test
6. Write ADR-010 comparing G1GC vs ZGC: pause times, throughput impact,
   operational complexity

### Memory leak detection protocol

Run a 72-hour endurance test at **1000 orders/sec sustained**:

- Sample heap size every 5 minutes via JMX / Micrometer gauge
- Heap must **stabilise** (flat line after JVM warm-up) — linear growth means
  there is a leak
- If heap exceeds **1.5 GB**, trigger an automatic heap dump:
  ```
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/tmp/order-processing-heap.hprof
  ```
- Analyse the heap dump with JProfiler `Heap Walker` — identify retained
  objects by reference chain

### Profiling deliverables (required for DEL-4)

- Heap allocation flamegraph (async-profiler `alloc` event)
- GC pause time distribution chart (p50, p95, p99 before and after tuning)
- Before/after comparison table: throughput, p99 latency, GC overhead %

---

## PER-2: Concurrency Optimisation

### Thread pool tuning (Phase 2 → validated in Phase 3)

1. Start with the baseline configuration in `02-architecture.md § AR-3`
   (core=8, max=16 for order executor)
2. Run load test at increasing concurrency levels: 1000, 5000, 10000 orders/sec
3. Record active thread count, queue depth, rejection count per test
4. Plot throughput vs core pool size to find the knee of the curve
5. Document the winning configuration and the reason in an ADR

### Lock contention analysis

1. Use JProfiler **Monitor Usage** view under load to identify the most
   contested monitors
2. Replace any `synchronized` blocks where reads dominate writes with
   `ReentrantReadWriteLock`:
    - `readLock()` for inventory snapshot reads
    - `writeLock()` for reservation writes
3. Measure the throughput improvement on the inventory check path

### Reactive vs synchronous comparison (required evidence for LO-2)

Run both implementations at 5000 concurrent orders and measure:

| Metric | Synchronous | Reactive | Target improvement |
|---|---|---|---|
| Max throughput | baseline | measured | > +100% |
| Thread count | baseline | measured | < −50% |
| Heap under load | baseline | measured | < −30% |
| p99 latency | baseline | measured | < −30% |

Document results in an ADR (ADR-002 extension or a new ADR).

---

## PER-3: Database Performance

All index DDL lives in `04-database.md § DB-1`. This section covers only the
performance targets and analysis tasks.

> **[OI-10]** Do not add CREATE INDEX SQL here. Refer to `04-database.md` as
> the single source of truth for all schema changes.

### Query performance targets

| Constraint | Target |
|---|---|
| Full table scans on tables > 1000 rows | None allowed |
| Queries using an index | > 90% |
| Connection pool reuse rate | > 95% |

### Required analysis tasks (Phase 3)

For every query in the hot path (create order, check inventory, list orders
by customer):

1. Run `EXPLAIN ANALYZE` on a dataset of 100,000+ orders
2. Confirm index is used (`Index Scan` or `Bitmap Index Scan`, not `Seq Scan`)
3. Record actual execution time in the performance report
4. Document the composite index strategy rationale (why `(customer_id, status,
   created_at DESC)` serves the order history query better than three separate
   indexes)

### Connection pool analysis

- Instrument HikariCP metrics via Micrometer: `hikaricp.connections.active`,
  `hikaricp.connections.idle`, `hikaricp.connections.pending`
- Peak active connections must not hit the pool maximum under normal load
  (headroom required for burst)
- Connection wait time p95 target: < 5 ms

---

## PER-4: Caching Strategy

### L1 — In-process (Caffeine)

```java
@Configuration
public class CacheConfiguration {

    @Bean
    public CaffeineCache productCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)                    // evict LRU beyond 10k entries
            .expireAfterWrite(10, TimeUnit.MINUTES) // absolute TTL
            .recordStats()                          // expose hit/miss metrics
            .build();
    }
}
```

**What to cache:**

| Cache name | Content | TTL | Invalidation trigger |
|---|---|---|---|
| `productCache` | Product details (id, name, price) | 10 min | Product update event |
| `idempotencyCache` | Idempotency key → response | 24 h | Expiry only |
| `customerOrderCount` | Count of orders per customer | 5 min | Order confirmed / cancelled |

### L2 — Distributed (Redis — optional, Phase 3 bonus)

- Inventory snapshots for read scaling across multiple instances
- Implement cache-aside pattern: read from Redis, fall through to PostgreSQL on
  miss, populate cache on hit
- Use versioned cache keys: `inventory:{productId}:{version}` — allows safe
  concurrent updates without explicit invalidation locks

### Cache invalidation rules

- Product cache: invalidate on any product update (price, name, availability)
- Inventory cache: invalidate on `OrderConfirmed` event (inventory permanently
  deducted)
- Never cache inventory reservation state — this must always read from the
  database to guarantee correctness under BR-3

### Cache metrics targets

| Metric | Target |
|---|---|
| Hit rate (product cache) | > 80% under steady load |
| Cache load time p95 | < 5 ms |
| Eviction rate | Monitor — high eviction indicates `maximumSize` is too low |

---

## NFR-5: Observability

### Structured logging

Use Logback with `logstash-logback-encoder` (JSON output). Every log line
must include the correlation ID from `RequestContext` (see `02-architecture.md
§ AR-5`).

**Log level policy:**

| Level | When to use |
|---|---|
| `ERROR` | Operation failed, order lost, unrecoverable state |
| `WARN` | Retry attempt, circuit breaker state change, slow query |
| `INFO` | Order created, payment authorised, order confirmed/cancelled |
| `DEBUG` | Disabled in production; use for local development only |

**PII rules (hard requirement):**
- Credit card numbers: log last 4 digits only — mask with `****-****-****-XXXX`
- Raw card numbers: never stored or logged anywhere
- Customer email/phone: not logged at INFO or above

### Prometheus metrics

Expose via `GET /actuator/prometheus`. Required counters and histograms:

| Metric name | Type | Labels |
|---|---|---|
| `orders_created_total` | Counter | `status` (CONFIRMED / FAILED) |
| `orders_failed_total` | Counter | `reason` (INVENTORY / PAYMENT / VALIDATION) |
| `order_processing_duration_seconds` | Histogram | — |
| `inventory_checks_total` | Counter | `result` (RESERVED / INSUFFICIENT) |
| `payment_authorizations_total` | Counter | `result` (SUCCESS / FAILED) |
| JVM metrics | Gauges | `jvm.memory.used`, `jvm.gc.pause`, `jvm.threads.live` |

All metrics registered via `MeterRegistry` in Micrometer — no manual
Prometheus client calls.

### Health check endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Full health: DB connectivity + disk space |
| `GET /actuator/health/liveness` | Kubernetes liveness probe — is the JVM alive? |
| `GET /actuator/health/readiness` | Kubernetes readiness probe — can it serve traffic? |

The readiness probe must return `DOWN` if the database connection pool is
exhausted or the circuit breaker is open — a degraded instance must not
receive traffic.