package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.application.port.in.CancelOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand.OrderItemCommand;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventPublisher;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.application.util.OrderIdGenerator;
import com.bowt.backend.orderprocessing.domain.event.OrderEvent;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.exception.ProductNotFoundException;
import com.bowt.backend.orderprocessing.domain.factory.OrderFactory;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.domain.model.ShippingAddress;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderType;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates FR-1..FR-6. Implements all three inbound ports so that
 * {@code UseCaseConfiguration} can register it as a <b>single</b> Spring
 * bean — fixing the SESSION_REFERENCE.md §3.6 bug where
 * {@code createOrderUseCase} and {@code queryOrderUseCase} each called
 * {@code new OrderService(...)} independently, producing two live instances.
 *
 * <p><b>ASSUMPTION FLAGGED:</b> {@code OrderFactory.createOrder(...)} below
 * is called with the signature
 * {@code createOrder(OrderType, String customerId, List<OrderItem> items,
 * ShippingAddress address, String paymentMethod)}.
 * Your actual {@code domain/factory/OrderFactory.java} (AR-2 Pattern 2, not
 * in this delivery) must expose a compatible signature — the master doc only
 * specifies {@code createOrder(OrderType, OrderRequest)} in the abstract, and
 * {@code OrderRequest} was never concretely defined. Adjust this call site to
 * match once the real factory is available; every other line in this class
 * is independent of that signature.
 *
 * <p>ADR-002 hybrid model: {@link #createOrder} is the synchronous baseline
 * (Phase 2, integrates cleanly with {@code @Transactional}/Hibernate);
 * {@link #processOrderReactive} is the {@code Mono<Order>} pipeline used for
 * the Phase 3 sync-vs-reactive comparison (PER-2). Both share the same
 * private step methods so the comparison is apples-to-apples.
 */
@Slf4j
public class OrderService implements CreateOrderUseCase, QueryOrderUseCase, CancelOrderUseCase {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderFactory orderFactory;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final OrderEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderFactory orderFactory,
                        InventoryService inventoryService,
                        PaymentService paymentService,
                        AuditService auditService,
                        OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderFactory = orderFactory;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------
    // CreateOrderUseCase — synchronous pipeline (Phase 2 baseline)
    // ------------------------------------------------------------------

    @Override
    public Order createOrder(CreateOrderCommand command) {
        Order order = buildOrder(command);

        Map<String, Boolean> availability = inventoryService.checkAvailabilityParallel(command.items());
        boolean allAvailable = availability.values().stream().allMatch(Boolean::booleanValue);

        if (!allAvailable) {
            order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
            orderRepository.save(order);
            String shortage = availability.entrySet().stream()
                    .filter(e -> !e.getValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(", "));
            throw new InsufficientInventoryException("Insufficient stock for: " + shortage, 0);
        }

        try {
            // P5: own REQUIRES_NEW + REPEATABLE_READ transaction — commits
            // independently of this method's transaction.
            inventoryService.reserveInventory(command.items());
        } catch (OptimisticLockException e) {
            // ADR-003: retries already exhausted inside the @Retryable aspect by this point.
            order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
            orderRepository.save(order);
            throw new InsufficientInventoryException("Concurrent reservation conflict", 0);
        }

        order.transitionTo(OrderStatus.PENDING_PAYMENT);

        try {
            PaymentGateway.PaymentResult result = paymentService.authorize(
                    new PaymentGateway.PaymentRequest(order.getId().toString(),
                            order.getTotalAmount(), command.paymentMethod())
            );

            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
            order.confirm(result.transactionId()); // PAYMENT_AUTHORIZED -> CONFIRMED, stamps confirmedAt + estimatedDeliveryDate

        } catch (PaymentFailedException e) {
            order.transitionTo(OrderStatus.PAYMENT_FAILED);
            orderRepository.save(order);
            inventoryService.releaseInventory(command.items()); // BR-6 — release within 5 min; done inline here
            auditService.logPaymentFailure(order, e.getMessage());
            throw e;
        }

        Order saved = orderRepository.save(order);
        auditService.logOrderCreation(saved);
        eventPublisher.publish(
                new OrderEvent(saved.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, Instant.now()));

        return saved;
    }

    // ------------------------------------------------------------------
    // CreateOrderUseCase — reactive pipeline (Phase 3 comparison, ADR-002)
    // ------------------------------------------------------------------

    public Mono<Order> processOrderReactive(CreateOrderCommand command) {
        return Mono.fromCallable(() -> buildOrder(command))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(order -> checkInventoryReactive(order, command))
                .flatMap(order -> authorizePaymentReactive(order, command))
                .flatMap(this::confirmOrderReactive)
                .doOnError(e -> log.warn("Reactive order pipeline failed: {}", e.getMessage()));
    }

    private Mono<Order> checkInventoryReactive(Order order, CreateOrderCommand command) {
        return Mono.fromCallable(() -> inventoryService.checkAvailabilityParallel(command.items()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(availability -> {
                    boolean allAvailable = availability.values().stream().allMatch(Boolean::booleanValue);
                    if (!allAvailable) {
                        // P6 fix: the blocking save + inventory reservation attempt is wrapped
                        // in its own boundedElastic subscription — never called directly inside
                        // a reactive operator body, which would block the Reactor event-loop thread.
                        return Mono.fromCallable(() -> {
                                    order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
                                    return orderRepository.save(order);
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.error(new InsufficientInventoryException("Insufficient stock", 0)));
                    }
                    order.transitionTo(OrderStatus.PENDING_PAYMENT);
                    return Mono.fromCallable(() -> {
                                inventoryService.reserveInventory(command.items());
                                return order;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(OptimisticLockException.class, e ->
                                    // P6: this is the exact bug domain-layer-review.md flagged —
                                    // orderRepository.save() must not run directly in onErrorMap;
                                    // it is wrapped in its own boundedElastic subscription here.
                                    Mono.fromCallable(() -> {
                                                order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
                                                return orderRepository.save(order);
                                            })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .then(Mono.error(
                                                    new InsufficientInventoryException("concurrent-conflict", 0)))
                            );
                });
    }

    private Mono<Order> authorizePaymentReactive(Order order, CreateOrderCommand command) {
        return Mono.fromCallable(() -> paymentService.authorize(
                        new PaymentGateway.PaymentRequest(order.getId().toString(),
                                order.getTotalAmount(), command.paymentMethod())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
                    order.confirm(result.transactionId());
                    return order;
                })
                .onErrorResume(PaymentFailedException.class, e ->
                        // P6 fix applied identically to the payment failure path.
                        Mono.fromCallable(() -> {
                                    order.transitionTo(OrderStatus.PAYMENT_FAILED);
                                    orderRepository.save(order);
                                    inventoryService.releaseInventory(command.items());
                                    auditService.logPaymentFailure(order, e.getMessage());
                                    return order;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.error(e))
                );
    }

    private Mono<Order> confirmOrderReactive(Order order) {
        return Mono.fromCallable(() -> {
                    Order saved = orderRepository.save(order);
                    auditService.logOrderCreation(saved);
                    eventPublisher.publish(
                            new OrderEvent(
                                    saved.getId(), OrderStatus.PENDING_PAYMENT,
                                    OrderStatus.CONFIRMED, Instant.now()
                            )
                    );
                    return saved;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ------------------------------------------------------------------
    // Shared order-construction step
    // ------------------------------------------------------------------

    private Order buildOrder(CreateOrderCommand command) {
        // I-2 fix applied at the use-case level too: one batch lookup, not
        // one query per item.
        List<String> productIds = command.items().stream().map(OrderItemCommand::productId).toList();
        List<Product> products = productRepository.findAllById(productIds);
        Map<String, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));

        List<OrderItem> orderItems = command.items().stream()
                .map(item -> {
                    Product product = byId.get(item.productId());
                    if (product == null) {
                        throw new ProductNotFoundException(item.productId());
                    }
                    return new OrderItem(item.productId(), product.getName(), item.quantity(), product.getPrice());
                })
                .toList();

        ShippingAddress address = new ShippingAddress(
                command.shippingAddress().street(),
                command.shippingAddress().city(),
                command.shippingAddress().state(),
                command.shippingAddress().postalCode(),
                command.shippingAddress().country());

        Order order = orderFactory.createOrder(OrderType.STANDARD, OrderIdGenerator.generate(),
                new OrderFactory.OrderCreationRequest(
                        command.customerId(), orderItems, command.paymentMethod(), address)
        );
        return orderRepository.save(order); // persist PENDING_VALIDATION baseline before downstream steps
    }

    // ------------------------------------------------------------------
    // QueryOrderUseCase (FR-5)
    // ------------------------------------------------------------------

    @Override
    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId);
    }

    @Override
    public PagedResult findByCustomerIdAndFilters(String customerId, OrderStatus status, Instant from,
                                                  Instant to, OrderRepository.Pageable pageable) {
        List<Order> orders = orderRepository.findByCustomerIdAndFilters(customerId, status, from, to, pageable);
        long total = orderRepository.countByCustomerIdAndFilters(customerId, status, from, to);
        return PagedResult.of(orders, pageable, total);
    }

    // ------------------------------------------------------------------
    // CancelOrderUseCase (FR-6, [OI-5])
    // ------------------------------------------------------------------

    @Override
    public Order cancelOrder(CancelOrderCommand command) {
        Order order = orderRepository.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + command.orderId()));

        OrderStatus previousStatus = order.getStatus();

        // [OI-5]: canTransitionTo() in the domain state machine already allows
        // both CONFIRMED -> CANCELLED and PAYMENT_AUTHORIZED -> CANCELLED;
        // transitionTo() throws InvalidOrderStateException for anything else.
        order.transitionTo(OrderStatus.CANCELLED);

        if (order.getPaymentTransactionId() != null) {
            paymentService.refund(order.getPaymentTransactionId(), order.getTotalAmount());
        }

        inventoryService.releaseInventory(toItemCommands(order));

        Order saved = orderRepository.save(order);
        auditService.logOrderCancellation(saved, command.reason());
        eventPublisher.publish(new OrderEvent(saved.getId(), previousStatus, OrderStatus.CANCELLED, Instant.now()));

        return saved;
    }

    private List<OrderItemCommand> toItemCommands(Order order) {
        return order.getItems().stream()
                .map(item -> new OrderItemCommand(item.getProductId(), item.getQuantity()))
                .toList();
    }
}