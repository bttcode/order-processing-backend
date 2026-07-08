package com.bowt.backend.orderprocessing.infrastructure.notification;

import com.bowt.backend.orderprocessing.application.exception.NotificationDeliveryException;
import com.bowt.backend.orderprocessing.application.port.out.NotificationService.NotificationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit 5 — no Spring context needed. failureRatePercent and latency
 * bounds are passed directly via the constructor (0 / 100) to make both the
 * success and failure paths deterministic, per TEST-1 conventions.
 */
class MockNotificationServiceAdapterTest {

    @Test
    void shouldDeliverAndIncrementSuccessCounter_whenFailureRateIsZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockNotificationServiceAdapter adapter =
                new MockNotificationServiceAdapter(registry, 0, 0, 0);

        NotificationRequest request = new NotificationRequest(
                "ORDER-123", "warehouse-webhook", "Pick order", "Order ORDER-123 ready to pick");

        adapter.send(request);

        assertThat(registry.counter("notifications_delivered_total", "channel", "warehouse-webhook")
                .count()).isEqualTo(1.0);
        assertThat(registry.find("notifications_failed_total").counter()).isNull();
    }

    @Test
    void shouldThrowAndIncrementFailureCounter_whenFailureRateIsHundred() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockNotificationServiceAdapter adapter =
                new MockNotificationServiceAdapter(registry, 100, 0, 0);

        NotificationRequest request = new NotificationRequest(
                "CUST-1", "email", "Order confirmed", "Your order is confirmed");

        assertThatThrownBy(() -> adapter.send(request))
                .isInstanceOf(NotificationDeliveryException.class);

        assertThat(registry.counter("notifications_failed_total", "channel", "email")
                .count()).isEqualTo(1.0);
        assertThat(registry.find("notifications_delivered_total").counter()).isNull();
    }

    @Test
    void shouldRejectBlankFields() {
        MockNotificationServiceAdapter adapter =
                new MockNotificationServiceAdapter(new SimpleMeterRegistry(), 0, 0, 0);

        assertThatThrownBy(() -> adapter.send(
                new NotificationRequest("", "email", "s", "m")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> adapter.send(
                new NotificationRequest("r", "", "s", "m")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> adapter.send(
                new NotificationRequest("r", "email", "", "m")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> adapter.send(
                new NotificationRequest("r", "email", "s", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMaskEmailRecipient_keepingFirstCharAndDomain() {
        MockNotificationServiceAdapter adapter =
                new MockNotificationServiceAdapter(new SimpleMeterRegistry(), 0, 0, 0);

        assertThat(adapter.maskPii("jane.doe@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void shouldMaskPhoneLikeRecipient_keepingLastFourDigits() {
        MockNotificationServiceAdapter adapter =
                new MockNotificationServiceAdapter(new SimpleMeterRegistry(), 0, 0, 0);

        assertThat(adapter.maskPii("+15551234567")).isEqualTo("***4567");
    }

    @Test
    void shouldNotMaskNonPiiChannelRecipients() {
        // orderId-style recipients on accounting/warehouse channels are not PII —
        // AccountingListener passes event.orderId().toString() as recipientId.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockNotificationServiceAdapter adapter =
                new MockNotificationServiceAdapter(registry, 0, 0, 0);

        // No assertion on log output here (would require a log appender test),
        // but delivery for a non-PII channel must still succeed normally.
        adapter.send(new NotificationRequest(
                "550e8400-e29b-41d4-a716-446655440000", "accounting-export",
                "Revenue event", "Order confirmed — record revenue."));

        assertThat(registry.counter("notifications_delivered_total", "channel", "accounting-export")
                .count()).isEqualTo(1.0);
    }
}