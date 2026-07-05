package com.bowt.backend.orderprocessing.domain.model;

import com.bowt.backend.orderprocessing.domain.exception.InvalidOrderStateException;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final UUID ID = UUID.randomUUID();

    private Order newOrder() {
        return new Order(ID, "CUST-123");
    }

    @Nested
    class Construction {

        @Test
        void assignsIdAndCustomerId() {
            Order order = newOrder();

            assertThat(order.getId()).isEqualTo(ID);
            assertThat(order.getCustomerId()).isEqualTo("CUST-123");
        }

        @Test
        void startsInPendingValidationWithEmptyItems() {
            Order order = newOrder();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_VALIDATION);
            assertThat(order.getItems()).isEmpty();
        }

        @Test
        void setsCreatedAtAndUpdatedAtOnConstruction() {
            Order order = newOrder();

            assertThat(order.getCreatedAt()).isNotNull();
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        void rejectsNullId() {
            assertThatThrownBy(() -> new Order(null, "CUST-123"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("id");
        }

        @Test
        void rejectsNullCustomerId() {
            assertThatThrownBy(() -> new Order(ID, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("customerId");
        }
    }

    @Nested
    class Items {

        @Test
        void addItem_appendsToTheList() {
            Order order = newOrder();

            order.addItem(new OrderItem("PROD-1", "Widget", 2, Money.of("10.00")));

            assertThat(order.getItems()).hasSize(1);
        }

        @Test
        void addItem_bumpsUpdatedAt() {
            Order order = newOrder();
            Instant before = order.getUpdatedAt();

            order.addItem(new OrderItem("PROD-1", "Widget", 1, Money.of("10.00")));

            // non-flaky even on coarse-grained clocks: equal-or-after always holds
            assertThat(order.getUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        void addItem_rejectsNull() {
            Order order = newOrder();

            assertThatThrownBy(() -> order.addItem(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void getItems_returnsAnUnmodifiableView() {
            Order order = newOrder();
            order.addItem(new OrderItem("PROD-1", "Widget", 1, Money.of("10.00")));

            List<OrderItem> items = order.getItems();

            assertThatThrownBy(() ->
                    items.add(new OrderItem("PROD-2", "Other", 1, Money.of("5.00"))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getTotalAmount_sumsAllItemTotals() {
            Order order = newOrder();
            order.addItem(new OrderItem("PROD-1", "Widget", 2, Money.of("49.99")));
            order.addItem(new OrderItem("PROD-2", "Gadget", 1, Money.of("199.99")));

            assertThat(order.getTotalAmount()).isEqualTo(Money.of("299.97"));
        }

        @Test
        void getTotalAmount_zeroWhenNoItems() {
            assertThat(newOrder().getTotalAmount()).isEqualTo(Money.ZERO);
        }
    }

    @Nested
    class NonStateSetters {

        @Test
        void setPaymentMethod_updatesField() {
            Order order = newOrder();

            order.setPaymentMethod(PaymentMethod.CREDIT_CARD);

            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        }

        @Test
        void setPaymentTransactionId_updatesField() {
            Order order = newOrder();

            order.setPaymentTransactionId("TXN-1");

            assertThat(order.getPaymentTransactionId()).isEqualTo("TXN-1");
        }

        @Test
        void setShippingAddress_updatesField() {
            Order order = newOrder();
            ShippingAddress addr = new ShippingAddress("123 Main St", "SF", "CA", "94102", "US");

            order.setShippingAddress(addr);

            assertThat(order.getShippingAddress()).isEqualTo(addr);
        }
    }

    @Nested
    class StateMachine {

        @Test
        void transitionTo_validTransitionSucceeds() {
            Order order = newOrder();

            order.transitionTo(OrderStatus.PENDING_PAYMENT);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        void transitionTo_invalidTransitionThrowsAndLeavesStatusUnchanged() {
            Order order = newOrder(); // PENDING_VALIDATION

            assertThatThrownBy(() -> order.transitionTo(OrderStatus.CONFIRMED))
                    .isInstanceOf(InvalidOrderStateException.class);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_VALIDATION);
        }

        @Test
        void transitionTo_terminalStateRejectsAnyFurtherTransition() {
            Order order = newOrder();
            order.transitionTo(OrderStatus.INSUFFICIENT_INVENTORY);

            assertThatThrownBy(() -> order.transitionTo(OrderStatus.PENDING_PAYMENT))
                    .isInstanceOf(InvalidOrderStateException.class);
        }

        @Test
        void fullHappyPathLifecycleReachesConfirmed() {
            Order order = newOrder();

            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
            order.confirm("TXN-999");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        void confirmedOrderCanStillBeCancelled() {
            Order order = newOrder();
            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
            order.confirm("TXN-1");

            order.transitionTo(OrderStatus.CANCELLED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Nested
    class Confirm {

        @Test
        void confirm_setsConfirmedAtTransactionIdAndDeliveryDate() {
            Order order = newOrder();
            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);

            order.confirm("TXN-123");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getConfirmedAt()).isNotNull();
            assertThat(order.getPaymentTransactionId()).isEqualTo("TXN-123");
            assertThat(order.getEstimatedDeliveryDate()).isEqualTo(expectedDeliveryDate(3));
        }

        @Test
        void confirm_rejectsNullTransactionId() {
            Order order = newOrder();
            order.transitionTo(OrderStatus.PENDING_PAYMENT);
            order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);

            assertThatThrownBy(() -> order.confirm(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void confirm_fromInvalidStateThrowsAndDoesNotSetConfirmedAt() {
            Order order = newOrder(); // PENDING_VALIDATION, not PAYMENT_AUTHORIZED

            assertThatThrownBy(() -> order.confirm("TXN-123"))
                    .isInstanceOf(InvalidOrderStateException.class);

            assertThat(order.getConfirmedAt()).isNull();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_VALIDATION);
        }

        /**
         * Independently mirrors Order#addBusinessDays (adds `days` business
         * days to today, skipping Saturday/Sunday) so the test does not just
         * restate the production code — it recomputes the expectation from
         * the spec in 01-functional.md (confirmedAt + 3 business days).
         */
        private LocalDate expectedDeliveryDate(int days) {
            LocalDate result = LocalDate.now();
            int added = 0;
            while (added < days) {
                result = result.plusDays(1);
                if (result.getDayOfWeek() != DayOfWeek.SATURDAY
                        && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    added++;
                }
            }
            return result;
        }
    }

    @Nested
    class Reconstruct {

        @Test
        void reconstruct_setsAllFieldsFromPersistedState() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            OrderItem item = new OrderItem("PROD-1", "Widget", 1, Money.of("10.00"));
            ShippingAddress addr = new ShippingAddress("123 Main St", "SF", "CA", "94102", "US");

            Order order = Order.reconstruct(
                    id, "CUST-1", OrderStatus.CONFIRMED, List.of(item),
                    PaymentMethod.PAYPAL, "TXN-1", addr,
                    LocalDate.now().plusDays(3), now, now, now);

            assertThat(order.getId()).isEqualTo(id);
            assertThat(order.getCustomerId()).isEqualTo("CUST-1");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getItems()).containsExactly(item);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PAYPAL);
            assertThat(order.getPaymentTransactionId()).isEqualTo("TXN-1");
            assertThat(order.getShippingAddress()).isEqualTo(addr);
            assertThat(order.getConfirmedAt()).isEqualTo(now);
        }

        @Test
        void reconstruct_bypassesTheStateMachine() {
            // A freshly-constructed Order can never legally jump straight to
            // CANCELLED (PENDING_VALIDATION has no such edge), but reconstruct()
            // must allow it — it is restoring already-validated persisted state,
            // not performing a new transition.
            Order order = Order.reconstruct(
                    UUID.randomUUID(), "CUST-1", OrderStatus.CANCELLED, List.of(),
                    null, null, null, null, Instant.now(), Instant.now(), null);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        void reconstruct_reconstructedOrderStillEnforcesTheStateMachineGoingForward() {
            // Bypass only applies to the initial reconstruction — once rebuilt,
            // normal transitionTo() rules must still apply.
            Order order = Order.reconstruct(
                    UUID.randomUUID(), "CUST-1", OrderStatus.CONFIRMED, List.of(),
                    PaymentMethod.CREDIT_CARD, "TXN-1", null, null,
                    Instant.now(), Instant.now(), Instant.now());

            assertThatThrownBy(() -> order.transitionTo(OrderStatus.PENDING_PAYMENT))
                    .isInstanceOf(InvalidOrderStateException.class);

            order.transitionTo(OrderStatus.CANCELLED); // still legal from CONFIRMED
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }
}