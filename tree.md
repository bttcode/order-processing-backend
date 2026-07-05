.
├── docker-compose.yml
├── pom.xml
└── src
    ├── main
    │   ├── java
    │   │   └── com
    │   │       └── bowt
    │   │           └── backend
    │   │               └── orderprocessing
    │   │                   ├── OrderProcessingApplication.java
    │   │                   ├── application
    │   │                   │   ├── config
    │   │                   │   │   ├── AsyncConfiguration.java
    │   │                   │   │   └── UseCaseConfiguration.java
    │   │                   │   ├── listener
    │   │                   │   │   ├── AccountingListener.java
    │   │                   │   │   ├── CustomerNotificationListener.java
    │   │                   │   │   └── WarehouseListener.java
    │   │                   │   ├── port
    │   │                   │   │   ├── in
    │   │                   │   │   │   ├── CancelOrderUseCase.java
    │   │                   │   │   │   ├── CreateOrderUseCase.java
    │   │                   │   │   │   └── QueryOrderUseCase.java
    │   │                   │   │   └── out
    │   │                   │   │       ├── IdempotencyStore.java
    │   │                   │   │       ├── NotificationService.java
    │   │                   │   │       ├── OrderEventListener.java
    │   │                   │   │       ├── OrderEventPublisher.java
    │   │                   │   │       ├── OrderRepository.java
    │   │                   │   │       ├── PaymentGateway.java
    │   │                   │   │       └── ProductRepository.java
    │   │                   │   ├── service
    │   │                   │   │   ├── AuditService.java
    │   │                   │   │   ├── InventoryService.java
    │   │                   │   │   ├── OrderService.java
    │   │                   │   │   └── PaymentService.java
    │   │                   │   └── util
    │   │                   │       └── OrderIdGenerator.java
    │   │                   ├── domain
    │   │                   │   ├── annotation
    │   │                   │   │   └── Retryable.java
    │   │                   │   ├── event
    │   │                   │   │   └── OrderEvent.java
    │   │                   │   ├── exception
    │   │                   │   │   ├── InsufficientInventoryException.java
    │   │                   │   │   ├── InvalidOrderStateException.java
    │   │                   │   │   ├── PaymentFailedException.java
    │   │                   │   │   └── ProductNotFoundException.java
    │   │                   │   ├── factory
    │   │                   │   │   └── OrderFactory.java
    │   │                   │   └── model
    │   │                   │       ├── Money.java
    │   │                   │       ├── Order.java
    │   │                   │       ├── OrderItem.java
    │   │                   │       ├── Product.java
    │   │                   │       ├── ShippingAddress.java
    │   │                   │       └── enumeration
    │   │                   │           ├── Currency.java
    │   │                   │           ├── OrderStatus.java
    │   │                   │           ├── OrderType.java
    │   │                   │           └── PaymentMethod.java
    │   │                   └── infrastructure
    │   │                       ├── config
    │   │                       │   ├── ExecutorConfiguration.java
    │   │                       │   └── RetryAspect.java
    │   │                       ├── notification
    │   │                       │   └── EventNotificationAdapter.java
    │   │                       ├── payment
    │   │                       │   ├── CreditCardPaymentStrategy.java
    │   │                       │   ├── CryptoPaymentStrategy.java
    │   │                       │   ├── MockPaymentGateway.java
    │   │                       │   ├── PayPalPaymentStrategy.java
    │   │                       │   ├── PaymentStrategy.java
    │   │                       │   └── PaymentStrategyFactory.java
    │   │                       ├── persistence
    │   │                       │   ├── DataSeeder.java
    │   │                       │   ├── adapter
    │   │                       │   │   ├── IdempotencyJpaAdapter.java
    │   │                       │   │   ├── OrderJpaAdapter.java
    │   │                       │   │   └── ProductJpaAdapter.java
    │   │                       │   ├── jpa
    │   │                       │   │   ├── IdempotencyKeyEntity.java
    │   │                       │   │   ├── JpaIdempotencyKeyRepository.java
    │   │                       │   │   ├── JpaOrderItemRepository.java
    │   │                       │   │   ├── JpaOrderRepository.java
    │   │                       │   │   ├── JpaProductRepository.java
    │   │                       │   │   ├── OrderEntity.java
    │   │                       │   │   ├── OrderItemEntity.java
    │   │                       │   │   ├── ProductEntity.java
    │   │                       │   │   └── ShippingAddressEmbeddable.java
    │   │                       │   ├── mapper
    │   │                       │   │   ├── OrderMapper.java
    │   │                       │   │   └── ProductMapper.java
    │   │                       │   └── scheduled
    │   │                       │       └── IdempotencyCleanupJob.java
    │   │                       └── rest
    │   │                           ├── config
    │   │                           │   ├── OpenApiConfiguration.java
    │   │                           │   └── WebMvcConfiguration.java
    │   │                           ├── dto
    │   │                           │   ├── CancelOrderRequest.java
    │   │                           │   ├── CancellationResponse.java
    │   │                           │   ├── CreateOrderRequest.java
    │   │                           │   ├── OrderItemRequest.java
    │   │                           │   ├── OrderItemResponse.java
    │   │                           │   └── ShippingAddressRequest.java
    │   │                           ├── exception
    │   │                           │   └── GlobalExceptionHandler.java
    │   │                           ├── idempotency
    │   │                           │   └── IdempotencyHandler.java
    │   │                           ├── ratelimit
    │   │                           │   ├── DeprecationInterceptor.java
    │   │                           │   └── RateLimitInterceptor.java
    │   │                           ├── security
    │   │                           │   └── ApiKeyInterceptor.java
    │   │                           ├── v1
    │   │                           │   ├── OrderControllerV1.java
    │   │                           │   ├── OrderResponseMapperV1.java
    │   │                           │   └── OrderResponseV1.java
    │   │                           └── v2
    │   │                               ├── OrderControllerV2.java
    │   │                               ├── OrderListResponseV2.java
    │   │                               ├── OrderResponseMapperV2.java
    │   │                               ├── OrderResponseV2.java
    │   │                               └── PageMetadata.java
    │   └── resources
    │       ├── application-local.yml
    │       ├── application-prod.yml
    │       ├── application.yml
    │       └── db
    │           └── migration
    │               └── V1__initial_schema.sql
    └── test
        ├── java
        │   └── com
        │       └── bowt
        │           └── backend
        │               └── orderprocessing
        └── resources
            └── application-test.yml