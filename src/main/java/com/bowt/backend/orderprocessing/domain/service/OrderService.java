package com.bowt.backend.orderprocessing.domain.service;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentResult;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.*;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2: order orchestration with parallel inventory checks, optimistic-lock
 * retry, payment-service delegation, and a Reactor reactive pipeline alternative.
 *
 * <h3>Two execution paths</h3>
 * <ol>
 *   <li><b>Synchronous</b> — {@link #createOrder}: used by the REST controller
 *       for Phase 2; straightforward sequential steps, fully transactional.</li>
 *   <li><b>Reactive</b> — {@link #processOrderReactive}: the Reactor
 *       {@code Mono<Order>} chain described in AR-3. Blocking calls
 *       (inventory, payment) are offloaded to {@code boundedElastic} so the
 *       event-loop thread stays non-blocked. Phase 2 wires this for comparison
 *       (PER-2 measurement table).</li>
 * </ol>
 *
 * <p>No Spring annotations on this class — it remains a pure domain service.
 * {@code @Transactional} lives on the interface methods or in
 * {@code UseCaseConfiguration} where this bean is registered.
 */
@Slf4j
public class OrderService implements CreateOrderUseCase, QueryOrderUseCase {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    public OrderService(OrderRepository orderRepository,
                        InventoryService inventoryService,
                        PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
    }

    // ── Synchronous pipeline (used by REST controller) ─────────────────────

    @Override
    @Transactional
    public Order createOrder(CreateOrderCommand command) {
        log.info("Creating order for customer={}, items={}",
                command.customerId(), command.items().size());

        // 1. Build domain Order shell
        Order order = new Order(command.customerId());
        order.setPaymentMethod(command.paymentMethod());
        applyShippingAddress(order, command);

        // 2. Parallel availability check — fans out via inventoryCheckExecutor
        //    Throws InsufficientInventoryException or IllegalArgumentException fast
        var productMap = inventoryService.checkAvailabilityParallel(command.items());

        // 3. Build OrderItems from the verified snapshot
        for (CreateOrderCommand.OrderItemCommand cmd : command.items()) {
            Product product = productMap.get(cmd.productId());
            order.addItem(new OrderItem(
                    product.getId(),
                    product.getName(),
                    cmd.quantity(),
                    product.getPrice()
            ));
        }

        // 4. Persist initial state (PENDING_VALIDATION)
        orderRepository.save(order);

        // 5. Atomically reserve inventory (REPEATABLE_READ + @Version retry)
        try {
            inventoryService.reserveInventory(command.items());
        } catch (InsufficientInventoryException | OptimisticLockException e) {
            // All 3 optimistic-lock retries exhausted, or genuine stock-out
            order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
            orderRepository.save(order);
            log.warn("Inventory reservation failed for order {}: {}", order.getId(), e.getMessage());
            throw (e instanceof InsufficientInventoryException ex)
                    ? ex
                    : new InsufficientInventoryException("concurrent-conflict", 0);
        }

        // 6. Transition → PENDING_PAYMENT, persist
        order.transitionTo(OrderStatus.PENDING_PAYMENT);
        orderRepository.save(order);

        // 7. Authorize payment (with retry + 5s timeout inside PaymentService)
        PaymentResult result;
        try {
            result = paymentService.authorize(order);
        } catch (PaymentFailedException e) {
            order.transitionTo(OrderStatus.PAYMENT_FAILED);
            // BR-6: release inventory within 5 minutes — Phase 2: inline synchronous
            inventoryService.releaseInventory(command.items());
            orderRepository.save(order);
            log.warn("Payment failed for order {}: {}", order.getId(), e.getMessage());
            throw e;
        }

        // 8. Confirm: PAYMENT_AUTHORIZED → CONFIRMED + timestamps + delivery date
        order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
        order.confirm(result.transactionId()); // → sets CONFIRMED + confirmedAt + estimatedDeliveryDate

        // 9. Hard-commit inventory deduction (no-op in Phase 2; hook for Phase 4)
        inventoryService.confirmDeduction(command.items());

        Order saved = orderRepository.save(order);
        log.info("Order {} confirmed, txn={}", saved.getId(), result.transactionId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId);
    }

    // ── Reactive pipeline (AR-3 — for PER-2 comparison measurement) ────────

    /**
     * Non-blocking Reactor pipeline equivalent to {@link #createOrder}.
     *
     * <p>All blocking operations (inventory DB reads/writes, payment gateway)
     * are wrapped in {@code Mono.fromCallable(...).subscribeOn(boundedElastic)}
     * so the event-loop thread is never parked.
     *
     * <p>Error handling mirrors the synchronous path: inventory failures and
     * payment failures transition the order to the appropriate terminal status.
     *
     * <p><b>Note:</b> {@code @Transactional} does not propagate across reactive
     * boundaries. Each {@code Mono.fromCallable} block executes in its own
     * transaction (managed by the Spring-injected proxy on the service methods).
     * Phase 4 will introduce a proper R2DBC or explicit transaction operator
     * if full reactive transactional semantics are required.
     */
    public Mono<Order> processOrderReactive(CreateOrderCommand command) {
        return Mono.fromCallable(() -> {
                    // Build order shell — cheap, CPU-only
                    Order order = new Order(command.customerId());
                    order.setPaymentMethod(command.paymentMethod());
                    applyShippingAddress(order, command);
                    return order;
                })
                .flatMap(order ->
                        // Parallel inventory check — blocks DB threads, not event-loop
                        Mono.fromCallable(() ->
                                        inventoryService.checkAvailabilityParallel(command.items()))
                                .subscribeOn(Schedulers.boundedElastic())
                                .map(productMap -> {
                                    command.items().forEach(cmd -> {
                                        Product p = productMap.get(cmd.productId());
                                        order.addItem(new OrderItem(
                                                p.getId(), p.getName(), cmd.quantity(), p.getPrice()));
                                    });
                                    return order;
                                })
                )
                .flatMap(order ->
                        // Reserve inventory — REPEATABLE_READ transaction, blocking
                        Mono.fromCallable(() -> {
                                    inventoryService.reserveInventory(command.items());
                                    order.transitionTo(OrderStatus.PENDING_PAYMENT);
                                    return orderRepository.save(order);
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorMap(OptimisticLockException.class, e -> {
                                    order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);
                                    orderRepository.save(order);
                                    return new InsufficientInventoryException("concurrent-conflict", 0);
                                })
                )
                .flatMap(order ->
                        // Authorize payment — potentially slow external call
                        Mono.fromCallable(() -> paymentService.authorize(order))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(result -> Mono.fromCallable(() -> {
                                    order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
                                    order.confirm(result.transactionId());
                                    inventoryService.confirmDeduction(command.items());
                                    return orderRepository.save(order);
                                }).subscribeOn(Schedulers.boundedElastic()))
                                .onErrorMap(PaymentFailedException.class, e -> {
                                    order.transitionTo(OrderStatus.PAYMENT_FAILED);
                                    // Release inventory on payment failure (BR-6)
                                    inventoryService.releaseInventory(command.items());
                                    orderRepository.save(order);
                                    return e;
                                })
                )
                .doOnError(ex -> log.error("Reactive pipeline failed: {}", ex.getMessage()));
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void applyShippingAddress(Order order, CreateOrderCommand cmd) {
        ShippingAddress addr = new ShippingAddress();
        addr.setStreet(cmd.street());
        addr.setCity(cmd.city());
        addr.setState(cmd.state());
        addr.setPostalCode(cmd.postalCode());
        addr.setCountry(cmd.country());
        order.setShippingAddress(addr);
    }
}