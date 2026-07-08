package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.application.port.in.CancelOrderUseCase.CancelOrderCommand;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase.CreateOrderCommand.OrderItemCommand;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventPublisher;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.exception.InvalidOrderStateException;
import com.bowt.backend.orderprocessing.application.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.exception.ProductNotFoundException;
import com.bowt.backend.orderprocessing.domain.factory.OrderFactory;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.Product;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderType;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.argThat;

/**
 * Unit tests for {@link OrderService}.
 * <p>
 * Scope, per TEST-1/TEST-2 split in 06-testing.md:
 * - This is an APPLICATION-layer test: every out-port
 * (OrderRepository, ProductRepository, OrderFactory, InventoryService,
 * PaymentService, AuditService, OrderEventPublisher) is mocked. No Spring
 * context, no DB. Domain objects (Order, OrderItem, Product, Money) are
 * real — they are pure and cheap to instantiate.
 * - Covers: happy path, every failure branch listed in tree-test.md
 * (INSUFFICIENT_INVENTORY on initial check, INSUFFICIENT_INVENTORY on
 * concurrent reservation conflict, PAYMENT_FAILED), product-not-found,
 * query delegation, and both cancellation branches (FR-6 / [OI-5]).
 * - Also covers the reactive pipeline (processOrderReactive) with
 * StepVerifier so the sync-vs-reactive behavioural parity required by
 * ADR-002 / PER-2 is actually asserted, not just assumed.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String CUSTOMER_ID = "CUST-123";
    private static final String PRODUCT_ID = "PROD-001";
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private OrderFactory orderFactory;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private AuditService auditService;
    @Mock
    private OrderEventPublisher eventPublisher;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productRepository, orderFactory,
                inventoryService, paymentService, auditService, eventPublisher);

        // save() mutates nothing; Order is mutated in place via transitionTo()/confirm(),
        // so the adapter contract here is "persist and return the same reference."
        lenient().when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * One product, one order item, quantity 2 @ $49.99 = $99.98 total.
     */
    private Product standardProduct() {
        return new Product(PRODUCT_ID, "SKU-1", "Wireless Mouse", Money.of(49.99), 50);
    }

    private CreateOrderCommand standardCommand() {
        return new CreateOrderCommand(
                CUSTOMER_ID,
                List.of(new OrderItemCommand(PRODUCT_ID, 2)),
                PaymentMethod.CREDIT_CARD,
                new CreateOrderCommand.ShippingAddressCommand(
                        "123 Main St", "San Francisco", "CA", "94102", "US"),
                UUID.randomUUID()
        );
    }

    /**
     * Mimics what the real OrderFactory produces: a fresh, fully-populated order.
     */
    private Order factoryBuiltOrder(Product product, int quantity) {
        Order order = new Order(UUID.randomUUID(), CUSTOMER_ID);
        order.addItem(new OrderItem(product.getId(), product.getName(), quantity, product.getPrice()));
        return order;
    }

    private void stubProductLookup(Product... products) {
        when(productRepository.findAllById(anyList_ProductIds()))
                .thenReturn(List.of(products));
    }

    @SuppressWarnings("unchecked")
    private List<String> anyList_ProductIds() {
        return any(List.class);
    }

    private PaymentGateway.PaymentResult successfulPaymentResult(String txnId) {
        PaymentGateway.PaymentResult result = mock(PaymentGateway.PaymentResult.class);
        lenient().when(result.transactionId()).thenReturn(txnId);
        return result;
    }

    // ------------------------------------------------------------------
    // createOrder — happy path
    // ------------------------------------------------------------------

    @Nested
    class CreateOrderHappyPath {

        @Test
        void confirmsOrder_whenInventoryAndPaymentSucceed() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, true));
            PaymentGateway.PaymentResult paymentResult = successfulPaymentResult("txn-abc");
            when(paymentService.authorize(any(PaymentGateway.PaymentRequest.class)))
                    .thenReturn(paymentResult);

            Order result = orderService.createOrder(standardCommand());

            assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(result.getPaymentTransactionId()).isEqualTo("txn-abc");
            assertThat(result.getTotalAmount()).isEqualTo(Money.of(99.98));
            assertThat(result.getConfirmedAt()).isNotNull();
            assertThat(result.getEstimatedDeliveryDate()).isNotNull();

            verify(inventoryService).reserveInventory(anyList());
            verify(auditService).logOrderCreation(result);
            verify(auditService, never()).logPaymentFailure(any(), anyString());
            verify(eventPublisher).publish(argThat(evt ->
                    evt.newStatus() == OrderStatus.CONFIRMED));
            // Baseline persist (PENDING_VALIDATION) + final persist (CONFIRMED) — never fewer.
            verify(orderRepository, atLeast(2)).save(any(Order.class));
        }
    }

    // ------------------------------------------------------------------
    // createOrder — failure branches
    // ------------------------------------------------------------------

    @Nested
    class CreateOrderFailureBranches {

        @Test
        void throwsProductNotFound_whenLineItemReferencesUnknownProduct() {
            // Repository returns nothing for the requested product id.
            when(productRepository.findAllById(anyList_ProductIds())).thenReturn(List.of());

            assertThatThrownBy(() -> orderService.createOrder(standardCommand()))
                    .isInstanceOf(ProductNotFoundException.class);

            // Must fail before any downstream side effect.
            verifyNoInteractions(inventoryService, paymentService, auditService, eventPublisher);
            verify(orderFactory, never()).createOrder(any(), any(), any());
        }

        @Test
        void transitionsToInsufficientInventory_whenAvailabilityCheckFails() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, false));

            assertThatThrownBy(() -> orderService.createOrder(standardCommand()))
                    .isInstanceOf(InsufficientInventoryException.class)
                    .hasMessageContaining(PRODUCT_ID);

            assertThat(builtOrder.getStatus()).isEqualTo(OrderStatus.INSUFFICIENT_INVENTORY);
            verify(orderRepository, atLeast(1)).save(builtOrder);
            // Never got as far as reserving stock or charging payment.
            verify(inventoryService, never()).reserveInventory(anyList());
            verifyNoInteractions(paymentService);
        }

        @Test
        void transitionsToInsufficientInventory_onOptimisticLockConflictDuringReservation() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, true));
            doThrow(new OptimisticLockException("version conflict"))
                    .when(inventoryService).reserveInventory(anyList());

            assertThatThrownBy(() -> orderService.createOrder(standardCommand()))
                    .isInstanceOf(InsufficientInventoryException.class)
                    .hasMessageContaining("Concurrent reservation conflict");

            // ADR-003: retries are exhausted inside the @Retryable aspect before this
            // exception surfaces — the service itself must not retry again, just fail fast.
            assertThat(builtOrder.getStatus()).isEqualTo(OrderStatus.INSUFFICIENT_INVENTORY);
            verifyNoInteractions(paymentService);
        }

        @Test
        void transitionsToPaymentFailed_releasesInventory_andLogsAudit_whenGatewayRejects() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, true));
            PaymentFailedException gatewayError = new PaymentFailedException("card declined");
            when(paymentService.authorize(any(PaymentGateway.PaymentRequest.class)))
                    .thenThrow(gatewayError);

            assertThatThrownBy(() -> orderService.createOrder(standardCommand()))
                    .isSameAs(gatewayError);

            assertThat(builtOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            // BR-6: inventory release must happen inline on payment failure.
            verify(inventoryService).releaseInventory(anyList());
            verify(auditService).logPaymentFailure(builtOrder, "Payment failed: card declined");
            verify(auditService, never()).logOrderCreation(any());
            verify(eventPublisher, never()).publish(any());
        }
    }

    // ------------------------------------------------------------------
    // Reactive pipeline (ADR-002 / PER-2 parity checks)
    // ------------------------------------------------------------------

    @Nested
    class ReactivePipeline {

        @Test
        void confirmsOrder_reactivePathMirrorsSyncHappyPath() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, true));
            PaymentGateway.PaymentResult paymentResult = successfulPaymentResult("txn-reactive");
            when(paymentService.authorize(any(PaymentGateway.PaymentRequest.class)))
                    .thenReturn(paymentResult);

            StepVerifier.create(orderService.processOrderReactive(standardCommand()))
                    .assertNext(order -> {
                        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                        assertThat(order.getPaymentTransactionId()).isEqualTo("txn-reactive");
                    })
                    .expectComplete()
                    .verify(Duration.ofSeconds(2));

            verify(eventPublisher).publish(argThat(evt -> evt.newStatus() == OrderStatus.CONFIRMED));
        }

        @Test
        void emitsInsufficientInventoryError_reactivePath_withoutBlockingEventLoop() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, false));

            StepVerifier.create(orderService.processOrderReactive(standardCommand()))
                    .expectErrorMatches(err -> err instanceof InsufficientInventoryException)
                    .verify(Duration.ofSeconds(2));

            assertThat(builtOrder.getStatus()).isEqualTo(OrderStatus.INSUFFICIENT_INVENTORY);
            verify(orderRepository, atLeast(1)).save(builtOrder);
        }

        @Test
        void emitsPaymentFailedError_reactivePath_andReleasesInventory() {
            Product product = standardProduct();
            Order builtOrder = factoryBuiltOrder(product, 2);

            stubProductLookup(product);
            when(orderFactory.createOrder(eq(OrderType.STANDARD), any(UUID.class), any()))
                    .thenReturn(builtOrder);
            when(inventoryService.checkAvailabilityParallel(anyList()))
                    .thenReturn(Map.of(PRODUCT_ID, true));
            PaymentFailedException gatewayError = new PaymentFailedException("timeout");
            when(paymentService.authorize(any(PaymentGateway.PaymentRequest.class)))
                    .thenThrow(gatewayError);

            StepVerifier.create(orderService.processOrderReactive(standardCommand()))
                    .expectErrorMatches(err -> err instanceof PaymentFailedException)
                    .verify(Duration.ofSeconds(2));

            assertThat(builtOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            verify(inventoryService).releaseInventory(anyList());
            verify(auditService).logPaymentFailure(eq(builtOrder), anyString());
        }
    }

    // ------------------------------------------------------------------
    // QueryOrderUseCase (FR-5)
    // ------------------------------------------------------------------

    @Nested
    class QueryOrder {

        @Test
        void findById_delegatesDirectlyToRepository() {
            UUID orderId = UUID.randomUUID();
            Order order = new Order(orderId, CUSTOMER_ID);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            Optional<Order> result = orderService.findById(orderId);

            assertThat(result).contains(order);
        }

        @Test
        void findById_returnsEmpty_whenOrderDoesNotExist() {
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

            assertThat(orderService.findById(orderId)).isEmpty();
        }

        @Test
        void findByCustomerIdAndFilters_queriesAndCountsWithSameFilters() {
            OrderRepository.Pageable pageable = mock(OrderRepository.Pageable.class);
            Order order = new Order(UUID.randomUUID(), CUSTOMER_ID);
            when(orderRepository.findByCustomerIdAndFilters(
                    eq(CUSTOMER_ID), eq(OrderStatus.CONFIRMED), any(), any(), eq(pageable)))
                    .thenReturn(List.of(order));
            when(orderRepository.countByCustomerIdAndFilters(
                    eq(CUSTOMER_ID), eq(OrderStatus.CONFIRMED), any(), any()))
                    .thenReturn(1L);

            var result = orderService.findByCustomerIdAndFilters(
                    CUSTOMER_ID, OrderStatus.CONFIRMED, null, null, pageable);

            assertThat(result).isNotNull();
            verify(orderRepository).findByCustomerIdAndFilters(
                    CUSTOMER_ID, OrderStatus.CONFIRMED, null, null, pageable);
            verify(orderRepository).countByCustomerIdAndFilters(
                    CUSTOMER_ID, OrderStatus.CONFIRMED, null, null);
        }
    }

    // ------------------------------------------------------------------
    // CancelOrderUseCase (FR-6, [OI-5])
    // ------------------------------------------------------------------

    @Nested
    class CancelOrder {

        @Test
        void cancelsConfirmedOrder_refundsPayment_releasesInventory_publishesEvent() {
            Order order = new Order(UUID.randomUUID(), CUSTOMER_ID);
            order.addItem(new OrderItem(PRODUCT_ID, "Wireless Mouse", 2, Money.of(49.99)));
            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
            order.confirm("txn-refundable"); // -> CONFIRMED, sets paymentTransactionId

            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

            Order result = orderService.cancelOrder(new CancelOrderCommand(
                    order.getId(), "Customer requested", UUID.randomUUID()));

            assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(paymentService).refund("txn-refundable", Money.of(99.98));
            verify(inventoryService).releaseInventory(anyList());
            verify(auditService).logOrderCancellation(result, "Customer requested");
            verify(eventPublisher).publish(argThat(evt ->
                    evt.oldStatus() == OrderStatus.CONFIRMED && evt.newStatus() == OrderStatus.CANCELLED));
        }

        @Test
        void cancelsAuthorizedOrder_beforeConfirmation_perOI5Decision() {
            // [OI-5]: PAYMENT_AUTHORIZED must also be cancellable, not just CONFIRMED.
            Order order = new Order(UUID.randomUUID(), CUSTOMER_ID);
            order.addItem(new OrderItem(PRODUCT_ID, "Wireless Mouse", 1, Money.of(19.99)));
            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
            order.setPaymentTransactionId("txn-held");

            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

            Order result = orderService.cancelOrder(new CancelOrderCommand(
                    order.getId(), "stuck payment", UUID.randomUUID()));

            assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(paymentService).refund("txn-held", order.getTotalAmount());
        }

        @Test
        void skipsRefund_whenOrderHasNoPaymentTransactionId() {
            // Defensive branch: should not be reachable via normal transitions today,
            // but the guard must hold if a cancellable state without a transaction ID
            // is ever introduced.
            Order order = new Order(UUID.randomUUID(), CUSTOMER_ID);
            order.addItem(new OrderItem(PRODUCT_ID, "Wireless Mouse", 1, Money.of(19.99)));
            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
            // paymentTransactionId intentionally left null

            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

            orderService.cancelOrder(new CancelOrderCommand(order.getId(), "no txn", UUID.randomUUID()));

            verifyNoInteractions(paymentService);
            verify(inventoryService).releaseInventory(anyList());
        }

        @Test
        void throwsIllegalArgument_whenOrderDoesNotExist() {
            UUID missingId = UUID.randomUUID();
            when(orderRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    orderService.cancelOrder(new CancelOrderCommand(missingId, "n/a", UUID.randomUUID())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(missingId.toString());

            verifyNoInteractions(paymentService, inventoryService, auditService, eventPublisher);
        }

        @Test
        void propagatesInvalidStateException_whenOrderNotInCancellableStatus() {
            // e.g. still PENDING_VALIDATION — domain state machine rejects the jump.
            Order order = new Order(UUID.randomUUID(), CUSTOMER_ID);
            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

            assertThatThrownBy(() ->
                    orderService.cancelOrder(new CancelOrderCommand(
                            order.getId(), "too early", UUID.randomUUID())))
                    .isInstanceOf(InvalidOrderStateException.class);

            verifyNoInteractions(paymentService, inventoryService, auditService, eventPublisher);
        }
    }
}