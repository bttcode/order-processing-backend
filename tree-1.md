application/
└── port/
└── out/
└── IdempotencyStore.java          # NEW outgoing port
#   Optional<StoredResponse> find(UUID key)
#   void save(UUID key, String operationHash, String responseBody, int status)
#   record StoredResponse(String operationHash, String responseBody, int status)

infrastructure/
├── persistence/
│   ├── jpa/
│   │   ├── IdempotencyKeyEntity.java       # NEW — maps idempotency_keys table
│   │   └── JpaIdempotencyKeyRepository.java
│   ├── adapter/
│   │   └── IdempotencyJpaAdapter.java      # NEW — implements IdempotencyStore
│   └── scheduled/
│       └── IdempotencyCleanupJob.java      # NEW — @Scheduled, purges expires_at < NOW() (OI-6)
│
├── rest/
│   ├── v1/
│   │   ├── OrderControllerV1.java          # existing controller, renamed/moved here
│   │   ├── dto/ (CreateOrderRequest, OrderResponse — totalAmount field)
│   │   └── mapper/OrderResponseMapperV1.java
│   ├── v2/
│   │   ├── OrderControllerV2.java          # NEW — total field, page metadata, from/to filters
│   │   ├── dto/ (renamed fields per API-3 table)
│   │   └── mapper/OrderResponseMapperV2.java
│   ├── idempotency/
│   │   └── IdempotencyHandler.java         # NEW — used inside both controllers:
│   │       #   checks IdempotencyStore before delegating to use case;
│   │       #   on hit: ResponseEntity.ok(cached) — 200, never 201
│   │       #   on conflict (same key, different payload hash): 409
│   │       #   on miss: execute use case, persist result, return 201/200 as appropriate
│   ├── security/
│   │   └── ApiKeyInterceptor.java          # NEW — runs first; 401 on missing/invalid X-API-Key
│   ├── ratelimit/
│   │   ├── RateLimitInterceptor.java       # NEW — runs after ApiKeyInterceptor; global Guava limiter (OI-15)
│   │   └── DeprecationInterceptor.java     # NEW — adds Warning header on all /v1/ responses
│   ├── exception/
│   │   └── GlobalExceptionHandler.java     # EXTEND existing — fix I-3/I-4 bugs, add 409/429 mappings
│   └── config/
│       ├── WebMvcConfiguration.java        # NEW — registers interceptor order: ApiKey → RateLimit → Deprecation
│       └── OpenApiConfiguration.java       # NEW — Swagger bean (NFR-6)