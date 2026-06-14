# 05 — API

<!--
MASTER SECTIONS COVERED
  § API-1: RESTful Endpoint Design (all 5 endpoints with request/response shapes)
  § API-2: Error Response Format (RFC 7807 Problem Details)
  § API-3: Versioning Strategy (v1 / v2, backward compatibility)
  § API-4: Rate Limiting (interceptor implementation, response headers)
  § NFR-6: Security (API key auth, input validation, PII masking)

IMPLEMENTATION PHASE
  Phase 1 (Weeks 1–4)  : Basic endpoints — POST /orders, GET /orders/{id}
                         No versioning, no idempotency yet
  Phase 2 (Weeks 5–8)  : Remaining endpoints (list, cancel), pagination
  Phase 5 (Weeks 17–20): API versioning (v1 + v2 simulation), idempotency
                         headers, RFC 7807 error handling, rate limiting,
                         Swagger/OpenAPI documentation (DOC-4)

OPEN ISSUES FROM SPLIT REVIEW
  [OI-1]  NFR-6 had no home in the original split plan. Placed here because
          API key auth and rate limiting are API-layer concerns.
  [OI-3]  The X-API-Key approach is lightweight by design. The RateLimitInterceptor
          in API-4 reads the key but does not validate it against a store — it
          only rate-limits by key value. If key validation is added later the
          interceptor must be refactored. Confirm scope before Phase 5.
  [OI-14] API-3 defines v2 as a "future simulation" with renamed fields
          (totalAmount → total). This is a breaking change that must be
          demonstrated in Phase 5 — implement both /v1 and /v2 controllers
          sharing the same use-case layer. The domain model does not change.
  [OI-15] The rate limiter in API-4 uses a single Guava RateLimiter instance
          (global, not per API key). For a real system this is wrong — one
          noisy key starves all others. Acceptable for learning scope but flag
          in the ADR and note the per-key fix (e.g. Caffeine-backed map of
          RateLimiters or Resilience4j RateLimiter registry).
-->

---

## Base URL

```
http://localhost:8080/api/v1
```

All endpoints require `X-API-Key: {apiKey}` header. Invalid or missing key
returns `401 Unauthorized`.

---

## API-1: RESTful Endpoints

### POST /orders — Create Order

```
POST /api/v1/orders
Content-Type: application/json
X-API-Key: {apiKey}
Idempotency-Key: {uuid}    ← required; see FR-8 in 01-functional.md

Request body:
{
  "customerId": "CUST-12345",
  "items": [
    { "productId": "PROD-001", "quantity": 2 },
    { "productId": "PROD-042", "quantity": 1 }
  ],
  "paymentMethod": "CREDIT_CARD",
  "shippingAddress": {
    "street":     "123 Main St",
    "city":       "San Francisco",
    "state":      "CA",
    "postalCode": "94102",
    "country":    "US"
  }
}

Response: 201 Created
{
  "orderId":     "550e8400-e29b-41d4-a716-446655440000",
  "status":      "PENDING_VALIDATION",
  "totalAmount": 299.97,
  "currency":    "USD",
  "createdAt":   "2026-05-17T10:30:00Z",
  "_links": {
    "self":   { "href": "/api/v1/orders/550e8400-e29b-41d4-a716-446655440000" },
    "cancel": { "href": "/api/v1/orders/550e8400-e29b-41d4-a716-446655440000/cancel" }
  }
}
```

**Notes:**
- Response returns immediately with `PENDING_VALIDATION` — processing is async
  or synchronous depending on the pipeline variant under test
- `_links` follows a minimal HATEOAS pattern (not full HAL); only self + cancel
- Idempotency-Key is mandatory — missing header → `400 Bad Request`

---

### GET /orders/{orderId} — Fetch Order

```
GET /api/v1/orders/{orderId}
X-API-Key: {apiKey}

Response: 200 OK
{
  "orderId":              "550e8400-e29b-41d4-a716-446655440000",
  "customerId":           "CUST-12345",
  "status":               "CONFIRMED",
  "items": [
    {
      "productId":    "PROD-001",
      "productName":  "Wireless Mouse",
      "quantity":     2,
      "unitPrice":    49.99,
      "totalPrice":   99.98
    }
  ],
  "totalAmount":          299.97,
  "currency":             "USD",
  "paymentMethod":        "CREDIT_CARD",
  "paymentTransactionId": "ch_3Nq8RK2eZvKYlo2C0A2B3C4D",
  "createdAt":            "2026-05-17T10:30:00Z",
  "confirmedAt":          "2026-05-17T10:30:05Z",
  "estimatedDeliveryDate":"2026-05-20"
}

Error: 404 Not Found (RFC 7807 body) if orderId does not exist
```

---

### GET /orders — List Orders (paginated + filtered)

```
GET /api/v1/orders
  ?customerId=CUST-12345
  &status=CONFIRMED
  &from=2026-01-01T00:00:00Z
  &to=2026-06-01T00:00:00Z
  &page=0
  &size=20
  &sort=createdAt,desc
X-API-Key: {apiKey}

Response: 200 OK
{
  "content": [ /* array of order summary objects */ ],
  "page": {
    "size":          20,
    "number":        0,
    "totalElements": 47,
    "totalPages":    3
  }
}
```

**Query parameters:**

| Param | Type | Default | Notes |
|---|---|---|---|
| `customerId` | string | — | Required for customer-scoped queries |
| `status` | enum | — | Optional filter — one value at a time |
| `from` | ISO-8601 | — | Inclusive lower bound on `created_at` |
| `to` | ISO-8601 | — | Inclusive upper bound on `created_at` |
| `page` | int | 0 | Zero-based page number |
| `size` | int | 20 | Max items per page |
| `sort` | string | `createdAt,desc` | Spring Data Pageable format |

---

### POST /orders/{orderId}/cancel — Cancel Order

```
POST /api/v1/orders/{orderId}/cancel
Content-Type: application/json
X-API-Key: {apiKey}
Idempotency-Key: {uuid}

Request body:
{
  "reason": "Customer requested cancellation"
}

Response: 200 OK
{
  "orderId":             "550e8400-e29b-41d4-a716-446655440000",
  "status":              "CANCELLED",
  "refundAmount":        299.97,
  "refundTransactionId": "re_3Nq8RK2eZvKYlo2C0X1Y2Z3A"
}

Error: 422 Unprocessable Entity if order is not in a cancellable status
```

---

### GET /actuator/health — Health Check

```
GET /actuator/health

Response: 200 OK
{
  "status": "UP",
  "components": {
    "db":        { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

Not versioned — health is always at `/actuator/health` regardless of API
version. Liveness and readiness probes at `/actuator/health/liveness` and
`/actuator/health/readiness`.

---

## API-2: Error Response Format (RFC 7807)

All error responses use the Problem Details format (RFC 7807). Spring Boot 3.x
provides `ProblemDetail` out of the box — use it; do not invent a custom error
DTO.

### Standard error response shape

```json
{
  "type":      "https://api.example.com/errors/insufficient-inventory",
  "title":     "Insufficient Inventory",
  "status":    422,
  "detail":    "Product PROD-001 has 5 units available, 10 were requested",
  "instance":  "/api/v1/orders",
  "timestamp": "2026-05-17T10:30:00Z",
  "errors": [
    {
      "field":   "items[0].quantity",
      "message": "Requested quantity exceeds available inventory"
    }
  ]
}
```

`errors` array is present only when the error maps to specific request fields
(Bean Validation failures, inventory per-product breakdown).

### Error type catalogue

| HTTP status | Error type | Trigger |
|---|---|---|
| 400 | `invalid-request` | Malformed JSON, missing required field |
| 401 | `unauthorized` | Missing or invalid `X-API-Key` |
| 404 | `resource-not-found` | Order ID does not exist |
| 409 | `idempotency-key-conflict` | Same key, different operation payload |
| 422 | `insufficient-inventory` | Stock check failed |
| 422 | `payment-failed` | Payment gateway rejection |
| 429 | `rate-limit-exceeded` | Too many requests |
| 500 | `internal-server-error` | Unhandled exception |
| 503 | `service-unavailable` | System overloaded or circuit breaker open |

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps every exception type
to the correct status and `ProblemDetail` body.

---

## API-3: Versioning Strategy

### URL versioning

| Version | Base path | Status |
|---|---|---|
| v1 | `/api/v1/` | Current — active |
| v2 | `/api/v2/` | Simulated in Phase 5 |

Both versions are implemented in separate controllers but share the same
application/domain layer. Only the DTO shapes and URL paths differ.

### Simulated v2 breaking changes (Phase 5 exercise)

| Change | v1 | v2 |
|---|---|---|
| Order total field name | `totalAmount` | `total` |
| List orders response | No explicit page metadata | `page` object with `totalElements` |
| Date range filtering | Not supported | `from` / `to` parameters |

### Backward compatibility policy

- v1 remains available for **12 months** after v2 launch
- v1 responses include a deprecation warning header:
  ```
  Warning: 299 - "API version 1 deprecated, migrate to v2 by 2027-06-01"
  ```
- Controllers enforce this via a `DeprecationInterceptor` that adds the header
  to all `/api/v1/` responses once v2 is active

> **[OI-14]** The v1/v2 split is a Phase 5 simulation exercise, not a real
> migration. Implement both controllers, wire them to the same use-case interfaces,
> and write a test that confirms v1 returns `totalAmount` and v2 returns `total`
> for the same underlying order.

---

## API-4: Rate Limiting

### Implementation

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // Global rate limiter — 100 req/min across all API keys
    // See [OI-15]: in production this should be per API key
    private final RateLimiter rateLimiter =
        RateLimiter.create(100.0 / 60.0); // tokens per second

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String apiKey = request.getHeader("X-API-Key");

        if (!rateLimiter.tryAcquire()) {
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.setHeader("X-RateLimit-Limit",     "100");
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("Retry-After",            "60");
            // Write RFC 7807 body manually here (pre-Spring DispatcherServlet)
            return false;
        }

        return true;
    }
}
```

> **[OI-15]** This is a single global `RateLimiter`. It limits total request
> rate across all callers combined, not per API key. For per-key limiting,
> use a `ConcurrentHashMap<String, RateLimiter>` keyed on the API key value,
> or use Resilience4j `RateLimiter` registry with a per-key configuration.
> Flag this explicitly in ADR-007 or a new ADR. The global approach is
> acceptable for the learning project but must be documented as a known gap.

### Response headers on every request

```
X-RateLimit-Limit:     100
X-RateLimit-Remaining: 47
X-RateLimit-Reset:     1684328400   ← Unix epoch seconds for next window reset
```

These headers must be set even on successful requests so clients can
implement proactive throttling.

---

## NFR-6: Security

**Priority:** MEDIUM — simplified for learning project scope.

### API key authentication

- All endpoints (except `/actuator/health`) require `X-API-Key` header
- Missing or unrecognised key → `401 Unauthorized` + RFC 7807 body
- Keys are statically configured in `application.yml` for this scope — no key
  store or rotation required
- Implemented as a `HandlerInterceptor` that runs before `RateLimitInterceptor`

### Input validation

- All request DTOs use Bean Validation 3.0 (JSR-380) annotations
- `@Valid` on every `@RequestBody` parameter in controllers
- Constraint violation → `400 Bad Request` + RFC 7807 body with per-field
  `errors` array (mapped by `GlobalExceptionHandler`)

### Data security

| Rule | Implementation |
|---|---|
| Credit card numbers | Log only last 4 digits; mask with `****-****-****-XXXX` |
| Raw card data | Never stored in any table; store only payment tokens |
| External payment calls | HTTPS only; mock adapter must also use HTTPS endpoint format |

### OpenAPI / Swagger configuration

```java
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI orderProcessingOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Order Processing API")
                .description("High-performance order management system")
                .version("v1.0.0"))
            .addSecurityItem(new SecurityRequirement().addList("apiKey"))
            .components(new Components()
                .addSecuritySchemes("apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")));
    }
}
```

Swagger UI accessible at `http://localhost:8080/swagger-ui.html` in all
non-production Spring profiles.

### Controller annotation example (for DOC-4 compliance)

```java
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order management endpoints")
public class OrderController {

    @PostMapping
    @Operation(
        summary = "Create a new order",
        description = "Validates inventory and authorizes payment",
        responses = {
            @ApiResponse(responseCode = "201", description = "Order created"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "422", description = "Inventory or payment failure")
        }
    )
    public ResponseEntity<OrderResponse> createOrder(
        @Valid @RequestBody CreateOrderRequest request,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) { ... }
}
```

Every endpoint must be documented with `@Operation` and at least the `201`/
`200`, `400`, `422`, and `500` response codes before Phase 6 is complete.