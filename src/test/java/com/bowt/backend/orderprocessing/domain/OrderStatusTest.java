package com.bowt.backend.orderprocessing.domain;

import com.bowt.backend.orderprocessing.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderStatusTest {

    @Test
    void pendingValidationCanGoToPendingPayment() {
        assertThat(OrderStatus.PENDING_VALIDATION.canTransitionTo(OrderStatus.PENDING_PAYMENT)).isTrue();
    }

    @Test
    void confirmedIsTerminalExceptCancellation() {
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.PENDING_PAYMENT)).isFalse();
    }

    @Test
    void paymentFailedIsTerminal() {
        for (OrderStatus next : OrderStatus.values()) {
            assertThat(OrderStatus.PAYMENT_FAILED.canTransitionTo(next)).isFalse();
        }
    }

    // TODO: test all status → status combinations exhaustively
}
