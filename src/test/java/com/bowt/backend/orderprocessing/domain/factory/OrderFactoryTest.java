package com.bowt.backend.orderprocessing.domain.factory;

import com.bowt.backend.orderprocessing.domain.factory.OrderFactory.OrderCreationRequest;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.OrderItem;
import com.bowt.backend.orderprocessing.domain.model.ShippingAddress;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderType;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain unit tests for {@link OrderFactory} — AR-2 pattern #2 (Factory).
 * Plain JUnit 5 — no Spring context. Covers all three {@link OrderType}
 * construction paths (StandardOrder / ExpressOrder / SubscriptionOrder) and
 * the per-type validation rules defined in {@code validateForType}.
 * <p>
 * Note on assumed collaborator APIs: {@code Order}, {@code OrderItem},
 * {@code ShippingAddress} are not part of this review's upload set. This
 * suite relies only on the constructor/accessor shapes already evidenced
 * elsewhere in the spec/ADRs (e.g. {@code new OrderItem(productId, qty,
 * unitPrice)} from 06-testing.md, {@code Money.of(double)}, and the
 * {@code Order(UUID id, String customerId)} constructor fixed in ADR review
 * P1/P3). Adjust accessor names if the real classes differ.
 */
class OrderFactoryTest {

    private final OrderFactory factory = new OrderFactory();

    private static OrderItem item(String productId, int qty) {
        return new OrderItem(productId, "PROD-TEST", qty, Money.of(19.99));
    }

    private static ShippingAddress address() {
        return new ShippingAddress("123 Main St", "San Francisco", "CA", "94102", "US");
    }

    private static List<OrderItem> items(int count) {
        List<OrderItem> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(item("PROD-" + i, 1));
        }
        return list;
    }

    // -----------------------------------------------------------------
    // Base validation — applies before the per-type switch runs
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("Base validation (all order types)")
    class BaseValidation {

        @Test
        @DisplayName("rejects a null OrderType before touching the request at all")
        void shouldRejectNullType() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(1), PaymentMethod.CREDIT_CARD, null);

            assertThatThrownBy(() -> factory.createOrder(null, UUID.randomUUID(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OrderType");
        }

        @Test
        @DisplayName("rejects an empty item list regardless of type")
        void shouldRejectEmptyItemsForEveryType() {
            for (OrderType type : OrderType.values()) {
                OrderCreationRequest request = new OrderCreationRequest(
                        "CUST-1", List.of(), PaymentMethod.CREDIT_CARD, address());

                assertThatThrownBy(() -> factory.createOrder(type, UUID.randomUUID(), request))
                        .as("type=%s", type)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("at least one item");
            }
        }
    }

    // -----------------------------------------------------------------
    // STANDARD
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("STANDARD orders")
    class StandardOrders {

        @Test
        @DisplayName("builds successfully without a shipping address and starts PENDING_VALIDATION")
        void shouldBuildWithoutShippingAddress() {
            UUID id = UUID.randomUUID();
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(2), PaymentMethod.CREDIT_CARD, null);

            Order order = factory.createOrder(OrderType.STANDARD, id, request);

            assertThat(order.getId()).isEqualTo(id);
            assertThat(order.getCustomerId()).isEqualTo("CUST-1");
            assertThat(order.getItems()).hasSize(2);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
            assertThat(order.getShippingAddress()).isNull();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_VALIDATION);
        }

        @Test
        @DisplayName("has no line-item ceiling — 11+ items are accepted, unlike EXPRESS")
        void shouldAcceptMoreThanExpressLimit() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(15), PaymentMethod.PAYPAL, null);

            Order order = factory.createOrder(OrderType.STANDARD, UUID.randomUUID(), request);

            assertThat(order.getItems()).hasSize(15);
        }

        @Test
        @DisplayName("preserves item order as supplied in the request")
        void shouldPreserveItemOrder() {
            OrderItem first = item("PROD-A", 1);
            OrderItem second = item("PROD-B", 2);
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", List.of(first, second), PaymentMethod.CREDIT_CARD, null);

            Order order = factory.createOrder(OrderType.STANDARD, UUID.randomUUID(), request);

            assertThat(order.getItems()).containsExactly(first, second);
        }
    }

    // -----------------------------------------------------------------
    // EXPRESS
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("EXPRESS orders")
    class ExpressOrders {

        @Test
        @DisplayName("builds successfully at exactly the 10-item ceiling with a shipping address")
        void shouldBuildAtLineItemCeiling() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(10), PaymentMethod.CREDIT_CARD, address());

            Order order = factory.createOrder(OrderType.EXPRESS, UUID.randomUUID(), request);

            assertThat(order.getItems()).hasSize(10);
            assertThat(order.getShippingAddress()).isNotNull();
        }

        @Test
        @DisplayName("rejects more than 10 line items")
        void shouldRejectMoreThanTenItems() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(11), PaymentMethod.CREDIT_CARD, address());

            assertThatThrownBy(() -> factory.createOrder(OrderType.EXPRESS, UUID.randomUUID(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Express orders are limited to 10");
        }

        @Test
        @DisplayName("rejects a missing shipping address")
        void shouldRejectMissingShippingAddress() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(1), PaymentMethod.CREDIT_CARD, null);

            assertThatThrownBy(() -> factory.createOrder(OrderType.EXPRESS, UUID.randomUUID(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("require a shipping address");
        }

        @Test
        @DisplayName("item-count check runs before the address check (fail-fast switch ordering)")
        void shouldFailOnItemCountBeforeAddress() {
            // Both rules are violated here (11 items AND no address). The
            // item-count branch is checked first inside the EXPRESS case, so
            // that message must be the one that surfaces.
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(11), PaymentMethod.CREDIT_CARD, null);

            assertThatThrownBy(() -> factory.createOrder(OrderType.EXPRESS, UUID.randomUUID(), request))
                    .hasMessageContaining("limited to 10");
        }
    }

    // -----------------------------------------------------------------
    // SUBSCRIPTION
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("SUBSCRIPTION orders")
    class SubscriptionOrders {

        @Test
        @DisplayName("builds successfully with both a shipping address and a payment method")
        void shouldBuildWithAddressAndPaymentMethod() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(1), PaymentMethod.CREDIT_CARD, address());

            Order order = factory.createOrder(OrderType.SUBSCRIPTION, UUID.randomUUID(), request);

            assertThat(order.getShippingAddress()).isNotNull();
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        }

        @Test
        @DisplayName("rejects a missing shipping address")
        void shouldRejectMissingShippingAddress() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(1), PaymentMethod.CREDIT_CARD, null);

            assertThatThrownBy(() -> factory.createOrder(OrderType.SUBSCRIPTION, UUID.randomUUID(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recurring shipment");
        }

        @Test
        @DisplayName("DOCUMENTS DEAD CODE: the null-paymentMethod branch in validateForType is unreachable")
        void paymentMethodNullCheckIsUnreachableThroughTheRecord() {
            // OrderCreationRequest's compact constructor already runs
            // Objects.requireNonNull(paymentMethod, ...), so a request with a
            // null payment method can never be constructed — meaning the
            // `if (request.paymentMethod() == null)` branch inside
            // OrderFactory.validateForType's SUBSCRIPTION case can never
            // execute. This test pins down that the record's constructor —
            // not the factory branch — is what actually protects callers
            // today. Worth a cleanup: either drop the dead branch from
            // OrderFactory, or relax paymentMethod to nullable on the record
            // and let the factory be the single source of truth for this rule.
            assertThatThrownBy(() ->
                    new OrderCreationRequest("CUST-1", items(1), null, address()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -----------------------------------------------------------------
    // OrderCreationRequest — record-level invariants
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("OrderCreationRequest invariants")
    class OrderCreationRequestInvariants {

        @Test
        @DisplayName("rejects a null customerId")
        void shouldRejectNullCustomerId() {
            assertThatThrownBy(() ->
                    new OrderCreationRequest(null, items(1), PaymentMethod.CREDIT_CARD, address()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("customerId");
        }

        @Test
        @DisplayName("rejects a null items list")
        void shouldRejectNullItemsList() {
            assertThatThrownBy(() ->
                    new OrderCreationRequest("CUST-1", null, PaymentMethod.CREDIT_CARD, address()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("items");
        }

        @Test
        @DisplayName("allows a null shippingAddress — not every order type requires one")
        void shouldAllowNullShippingAddress() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(1), PaymentMethod.CREDIT_CARD, null);

            assertThat(request.shippingAddress()).isNull();
        }

        @Test
        @DisplayName("defensively copies the items list — mutating the source after construction has no effect")
        void shouldDefensivelyCopyItems() {
            List<OrderItem> source = new ArrayList<>(items(2));
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", source, PaymentMethod.CREDIT_CARD, address());

            source.add(item("PROD-EXTRA", 1));

            assertThat(request.items()).hasSize(2);
        }

        @Test
        @DisplayName("returned items list is immutable")
        void shouldReturnImmutableItemsList() {
            OrderCreationRequest request = new OrderCreationRequest(
                    "CUST-1", items(1), PaymentMethod.CREDIT_CARD, address());

            assertThatThrownBy(() -> request.items().add(item("PROD-X", 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}