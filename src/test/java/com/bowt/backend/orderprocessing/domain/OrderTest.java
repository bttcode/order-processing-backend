package com.bowt.backend.orderprocessing.domain;

import com.bowt.backend.orderprocessing.domain.model.*;
import com.bowt.backend.orderprocessing.domain.exception.InvalidOrderStateException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderTest {

    @Test
    void calculatesTotalAmountCorrectly() {
        Order order = new Order("CUST-001");
        order.addItem(new OrderItem("PROD-1", "Widget", 2, Money.of(49.99)));
        order.addItem(new OrderItem("PROD-2", "Gadget", 1, Money.of(199.99)));

        assertThat(order.getTotalAmount()).isEqualTo(Money.of(299.97));
    }

    @Test
    void rejectsNegativeQuantity() {
        Order order = new Order("CUST-001");
        assertThatThrownBy(() ->
                order.addItem(new OrderItem("PROD-1", "Widget", -1, Money.of(10.00)))
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be positive");
    }

    @Test
    void rejectsZeroQuantity() {
        Order order = new Order("CUST-001");
        assertThatThrownBy(() ->
                order.addItem(new OrderItem("PROD-1", "Widget", 0, Money.of(10.00)))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmsFromPaymentAuthorized() {
        Order order = new Order("CUST-001");
        order.transitionTo(OrderStatus.PENDING_PAYMENT);
        order.transitionTo(OrderStatus.PAYMENT_AUTHORIZED);
        order.confirm("TXN-123");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();
        assertThat(order.getEstimatedDeliveryDate()).isNotNull();
        assertThat(order.getPaymentTransactionId()).isEqualTo("TXN-123");
    }

    @Test
    void rejectsConfirmationFromPaymentFailed() {
        Order order = new Order("CUST-001");
        order.transitionTo(OrderStatus.PENDING_PAYMENT);
        order.transitionTo(OrderStatus.PAYMENT_FAILED);

        assertThatThrownBy(() -> order.confirm("TXN-123"))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void rejectsInvalidTransition() {
        Order order = new Order("CUST-001"); // PENDING_VALIDATION
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.CONFIRMED))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    // TODO: test all valid state machine paths
    // TODO: test estimatedDeliveryDate skips weekends
}
