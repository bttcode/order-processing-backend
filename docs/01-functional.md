# 01 — Functional Requirements

<!--
MASTER SECTIONS COVERED
  § Functional Requirements: FR-1 through FR-8

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4)  : FR-1 (Order Creation), FR-5 (Status Query) — synchronous baseline
  Phase 2 (Weeks 5–8)  : FR-2 (Inventory Validation) with concurrency, FR-3 (Payment),
                         FR-4 (Confirmation)
  Phase 4 (Weeks 13–16): FR-6 (Cancellation), FR-7 (Events) — after arch refactor
  Phase 5 (Weeks 17–20): FR-8 (Idempotency) — implemented as API-layer cross-cut

OPEN ISSUES FROM SPLIT REVIEW
  [OI-4] FR-7 (Event Notifications) requires "at-least-once delivery" but uses
         in-process Observer pattern. There is no durable event store or outbox.
         This is intentional for the learning scope but means the guarantee holds
         only within a single JVM. Flag this in ADR-002 and accept the constraint
         explicitly — do not silently assume durability.
  [OI-5] FR-6 (Cancellation) says "Only orders in CONFIRMED status can be
         cancelled" but the order lifecycle also has PAYMENT_AUTHORIZED as a
         transient state before CONFIRMED. Clarify whether PAYMENT_AUTHORIZED
         orders can be cancelled (and refunded) or must wait for CONFIRMED.
         Interim decision: treat PAYMENT_AUTHORIZED as cancellable to avoid
         an unrecoverable payment-held state.
  [OI-6] FR-8 (Idempotency) stores results in `idempotency_keys` table (24-hour
         TTL). There is no defined cleanup job. Add a scheduled task or
         Flyway-compatible TTL cleanup in Phase 5 scope.
-->

---

## Order lifecycle state machine

```
PENDING_VALIDATION
    │
    ├─► INSUFFICIENT_INVENTORY   (terminal — inventory check failed)
    │
    └─► PENDING_PAYMENT
            │
            ├─► PAYMENT_FAILED   (terminal — all retries exhausted)
            │
            └─► PAYMENT_AUTHORIZED
                    │
                    └─► CONFIRMED
                            │
                            └─► CANCELLED   (before shipment only)
```

---

## FR-1: Order Creation

**Priority:** CRITICAL | **Phase:** 1

**Description:** Accept an order creation request, validate all fields, persist
with initial status, and return the assigned order ID.

### Acceptance criteria

- Accept: customer ID, list of `{ productId, quantity }` items, shipping address,
  payment method
- Validate all required fields are present → 400 on missing
- Validate product IDs exist in the seeded catalog → 422 on unknown ID
- Validate quantities are positive integers → 422 on invalid quantity
- Validate shipping address format (street, city, state, postalCode, country)
- Return order ID (UUID) and initial status `PENDING_VALIDATION`
- Order ID generation: `UUID.randomUUID()` or a time-ordered alternative
  (e.g. UUIDv7) — document the choice in an ADR if time-ordered is chosen

### Error scenarios

| Condition | HTTP status |
|---|---|
| Missing required fields | 400 Bad Request + RFC 7807 body |
| Invalid product ID | 422 Unprocessable Entity |
| Invalid quantity (zero, negative, non-integer) | 422 Unprocessable Entity |

---

## FR-2: Inventory Validation

**Priority:** CRITICAL | **Phase:** 2

**Description:** Check and atomically reserve inventory for all order items
concurrently. No overselling under any concurrency level.

### Acceptance criteria

- Query current inventory levels for all products **in parallel**
  (`CompletableFuture.allOf()`)
- Reserve inventory atomically only if **all** items are available
- If any single item is insufficient, release all reservations and fail the whole
  order
- Handle concurrent order attempts for the same product without race conditions
- Update order status to `PENDING_PAYMENT` (success) or
  `INSUFFICIENT_INVENTORY` (failure)
- Log inventory check duration as a Micrometer metric

### Race condition handling

- Use optimistic locking with `@Version` on `ProductEntity`
- Retry on `OptimisticLockException` — maximum 3 attempts, exponential backoff
- If all 3 attempts fail (high contention), transition order to
  `INSUFFICIENT_INVENTORY`
- The test in `06-testing.md § TEST-4` is the acceptance gate for this behaviour

---

## FR-3: Payment Processing

**Priority:** CRITICAL | **Phase:** 2

**Description:** Authorize payment via the configured payment gateway adapter.
Support multiple payment methods through the Strategy pattern.

### Acceptance criteria

- Support payment methods: `CREDIT_CARD`, `PAYPAL`, `CRYPTO`
- Call the payment gateway adapter (mock implementation in infrastructure layer)
- Enforce a 5-second authorization timeout — treat timeout as a failure
- On failure: retry with exponential backoff, maximum 3 attempts
- On exhausted retries: transition to `PAYMENT_FAILED`, trigger inventory release
  (BR-6 — within 5 minutes)
- On success: transition to `PAYMENT_AUTHORIZED`, store `paymentTransactionId`
- `paymentTransactionId` must be stored for reconciliation — never nullable after
  authorization succeeds

### Payment gateway port interface

```java
interface PaymentGateway {
    PaymentResult authorize(PaymentRequest request);
    void capture(String transactionId);
    void refund(String transactionId, Money amount);
}
```

This interface lives in `application/port/out/`. Infrastructure adapters
(`StripePaymentAdapter`, `PayPalPaymentAdapter`, `MockPaymentGateway`) implement
it. See `02-architecture.md § AR-2` for the Adapter/Strategy pattern split.

---

## FR-4: Order Confirmation

**Priority:** CRITICAL | **Phase:** 2

**Description:** Finalize an order after payment authorization. Permanently commit
inventory and notify downstream systems.

### Acceptance criteria

- Permanently deduct inventory (convert reservation to hard deduction)
- Update order status to `CONFIRMED`
- Stamp `confirmedAt` timestamp
- Calculate `estimatedDeliveryDate` = `confirmedAt` + 3 business days
  (skip Saturday/Sunday; no public holiday handling required for this scope)
- Emit `OrderConfirmed` event to all registered `OrderEventListener` subscribers
- Return the complete order details in the HTTP response

---

## FR-5: Order Status Query

**Priority:** HIGH | **Phase:** 1 (basic), Phase 2 (filtering/pagination)

**Description:** Allow retrieval of a single order or paginated order history
with filtering.

### Acceptance criteria

**Single order:**
- `GET /orders/{orderId}` → full order detail or 404 if not found

**Order history:**
- `GET /orders?customerId={id}` → paginated list, default page size 20
- Filter by `status` (one of: `PENDING_VALIDATION`, `PENDING_PAYMENT`,
  `PAYMENT_AUTHORIZED`, `CONFIRMED`, `CANCELLED`, `INSUFFICIENT_INVENTORY`,
  `PAYMENT_FAILED`)
- Filter by date range: `from` / `to` as ISO-8601 timestamps
- Default sort: `createdAt DESC`
- Pagination response includes `totalElements` and `totalPages`

---

## FR-6: Order Cancellation

**Priority:** MEDIUM | **Phase:** 4

**Description:** Cancel an order and issue a refund before shipment.

### Acceptance criteria

- Only orders in `CONFIRMED` status (and by `[OI-5]` also `PAYMENT_AUTHORIZED`)
  can be cancelled
- Call `PaymentGateway.refund()` — store the refund transaction ID
- Restore inventory quantities (reverse the hard deduction)
- Update order status to `CANCELLED`
- Emit `OrderCancelled` event
- Log cancellation `reason` and timestamp

> **[OI-5]** The original spec says CONFIRMED only. Decision: also allow
> PAYMENT_AUTHORIZED cancellations. Rationale: leaving an authorized payment
> without a path to cancellation creates an unrecoverable stuck state if
> CONFIRMED is never reached due to a system fault. Confirm with stakeholders
> before Phase 4 implementation.

---

## FR-7: Event Notifications

**Priority:** MEDIUM | **Phase:** 4

**Description:** Notify downstream services of every order status transition via
an in-process Observer pattern.

### Acceptance criteria

- Observer pattern: implement `OrderEventListener` interface
- Registered subscribers: `WarehouseListener`, `AccountingListener`,
  `CustomerNotificationListener`
- Notification dispatch is **asynchronous** (non-blocking relative to the main
  order pipeline — use a dedicated executor or Spring `@Async`)
- Delivery guarantee: **at-least-once within the JVM process**
- Event payload: `{ orderId, oldStatus, newStatus, occurredAt }`

> **[OI-4]** At-least-once across process restarts requires a durable outbox or
> message broker (Kafka, RabbitMQ). That is out of scope for Phase 4. The
> guarantee is JVM-scoped only. Document this constraint explicitly in ADR-002
> rather than leaving it implicit.

---

## FR-8: Idempotency

**Priority:** CRITICAL | **Phase:** 5

**Description:** All mutating operations are idempotent — a request replayed with
the same `Idempotency-Key` returns the original response without side effects.

### Acceptance criteria

- All write endpoints accept `Idempotency-Key: <UUID>` request header (required)
- On first receipt: execute the operation, persist the result (response body +
  HTTP status) in `idempotency_keys` table with 24-hour TTL
- On duplicate receipt (same key, same operation): return the cached response
  with `200 OK` (not `201 Created`)
- On conflict (same key, **different** operation payload): return `409 Conflict`
- Applies to: `POST /orders`, `POST /orders/{id}/cancel`, payment authorization
  (internally)

### Applicable operations

| Endpoint | Idempotency behaviour |
|---|---|
| `POST /orders` | Create once, replay returns original order |
| `POST /orders/{id}/cancel` | Cancel once, replay returns original cancellation |
| Payment authorization (internal) | Keyed on order ID to prevent double-charge |

> **[OI-6]** The `idempotency_keys` table has a TTL column but no cleanup job
> defined. Add a `@Scheduled` task to purge expired rows, or use a Postgres
> `pg_cron` job. Without it, the table grows unboundedly. Resolve in Phase 5
> implementation scope.