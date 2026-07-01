domain/                                   # zero framework deps — verify via ArchUnit + a Spring-less test run
├── model/
│   ├── Order.java                        # NO @Setter; status mutates only via transitionTo()
│   ├── OrderItem.java
│   ├── OrderStatus.java
│   ├── Money.java
│   ├── Product.java                      # add deductStock()/restoreStock() (P9) — keeps stock math in the aggregate
│   └── ShippingAddress.java
├── factory/
│   └── OrderFactory.java                 # Pattern 2 — pure construction logic, no I/O, no Spring
├── decorator/
│   ├── OrderEnhancement.java
│   ├── GiftWrappingDecorator.java
│   ├── InsuranceDecorator.java
│   └── PriorityHandlingDecorator.java    # Pattern 4 — pure, chainable, no I/O
├── event/
│   ├── OrderEvent.java
│   └── OrderEventPublisher.java          # outgoing port interface only — no listener impls here
├── exception/
│   ├── InsufficientInventoryException.java
│   ├── InvalidOrderStateException.java
│   └── PaymentFailedException.java
└── annotation/
└── Retryable.java                        # marker annotation, zero Spring import — OK to keep here

application/                              # use-case orchestration; Spring annotations allowed
├── port/
│   ├── in/
│   │   ├── CreateOrderUseCase.java
│   │   ├── QueryOrderUseCase.java
│   │   └── CancelOrderUseCase.java       # MISSING — required for FR-6, build now
│   └── out/
│       ├── OrderRepository.java
│       ├── ProductRepository.java
│       ├── PaymentGateway.java           # Pattern 5 (Adapter) — provider abstraction
│       ├── PaymentStrategy.java          # Pattern 1 (Strategy) — method abstraction, separate from above
│       └── NotificationService.java      # MISSING — outgoing port for Observer dispatch
├── service/                              # ← InventoryService and PaymentService MOVE HERE (P2 fix, Option A)
│   ├── OrderService.java                 # implements CreateOrderUseCase + QueryOrderUseCase + CancelOrderUseCase
│   ├── InventoryService.java             # @Transactional(REQUIRES_NEW, REPEATABLE_READ) lives here now, legally
│   ├── PaymentService.java               # @Transactional / executor wiring lives here now, legally
│   └── AuditService.java                 # @Transactional(REQUIRES_NEW)
├── listener/
│   ├── WarehouseListener.java            # Pattern 3 (Observer) — these DO I/O (notify external systems)
│   ├── AccountingListener.java           # so they belong here, not in domain/, registered as Spring beans
│   └── CustomerNotificationListener.java
└── config/
└── UseCaseConfiguration.java             # fix: ONE OrderService @Bean, exposed as both in-ports

infrastructure/
├── persistence/
│   ├── util/
│   │   └── OrderIdGenerator.java         # MOVED here from domain/util (P1/I-1 fix)
│   ├── jpa/     (OrderEntity, ProductEntity, OrderItemEntity, repositories)
│   ├── adapter/ (OrderJpaAdapter, ProductJpaAdapter)
│   ├── mapper/  (OrderMapper, ProductMapper)
│   └── config/  (DatabaseConfiguration)
├── payment/
│   ├── MockPaymentGateway.java           # MOVED out of persistence/payment (I-5 fix)
│   ├── StripePaymentAdapter.java         # second PaymentGateway impl — needed for the adapter-swap demo
│   ├── CreditCardPaymentStrategy.java    # PaymentStrategy impls (Pattern 1)
│   ├── PayPalPaymentStrategy.java
│   ├── CryptoPaymentStrategy.java
│   └── PaymentStrategyFactory.java       # resolves strategy by PaymentMethod enum
├── notification/
│   └── EventNotificationAdapter.java     # implements domain's OrderEventPublisher, dispatches to listener/ via @Async
├── config/
│   ├── ExecutorConfiguration.java
│   └── RetryAspect.java
└── rest/
├── OrderController.java
├── dto/
└── exception/GlobalExceptionHandler.java