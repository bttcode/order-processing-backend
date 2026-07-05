package com.bowt.backend.orderprocessing.domain.event;

import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain unit tests for {@link OrderEvent} — the payload published by the
 * Observer pattern (AR-2 §3) and consumed by WarehouseListener,
 * AccountingListener, and CustomerNotificationListener.
 * <p>
 * Plain JUnit 5 — no Spring context, no mocking framework. This class must
 * compile and run with zero Spring/Hibernate jars on the classpath (NFR-4).
 */
class OrderEventTest {

    private final UUID orderId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Nested
    @DisplayName("Construction / compact-constructor validation")
    class Construction {

        @Test
        @DisplayName("creates a valid event with all fields populated")
        void shouldCreateValidEvent() {
            OrderEvent event = new OrderEvent(orderId, OrderStatus.PENDING_VALIDATION,
                    OrderStatus.PENDING_PAYMENT, now);

            assertThat(event.orderId()).isEqualTo(orderId);
            assertThat(event.oldStatus()).isEqualTo(OrderStatus.PENDING_VALIDATION);
            assertThat(event.newStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(event.occurredAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("allows a null oldStatus for the very first transition (order creation)")
        void shouldAllowNullOldStatusOnCreation() {
            OrderEvent event = new OrderEvent(orderId, null, OrderStatus.PENDING_VALIDATION, now);

            assertThat(event.oldStatus()).isNull();
            assertThat(event.newStatus()).isEqualTo(OrderStatus.PENDING_VALIDATION);
        }

        @Test
        @DisplayName("rejects a null orderId")
        void shouldRejectNullOrderId() {
            assertThatThrownBy(() ->
                    new OrderEvent(null, OrderStatus.PENDING_VALIDATION, OrderStatus.CONFIRMED, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("orderId");
        }

        @Test
        @DisplayName("rejects a null newStatus")
        void shouldRejectNullNewStatus() {
            assertThatThrownBy(() ->
                    new OrderEvent(orderId, OrderStatus.PENDING_VALIDATION, null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("newStatus");
        }

        @Test
        @DisplayName("rejects a null occurredAt")
        void shouldRejectNullOccurredAt() {
            assertThatThrownBy(() ->
                    new OrderEvent(orderId, OrderStatus.PENDING_VALIDATION, OrderStatus.CONFIRMED, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("occurredAt");
        }
    }

    @Nested
    @DisplayName("of(...) convenience factory")
    class OfFactory {

        @Test
        @DisplayName("stamps occurredAt with (approximately) the current instant")
        void shouldStampCurrentInstant() {
            Instant before = Instant.now();
            OrderEvent event = OrderEvent.of(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED);
            Instant after = Instant.now();

            assertThat(event.occurredAt())
                    .isBetween(before.minus(1, ChronoUnit.MILLIS), after.plus(1, ChronoUnit.MILLIS));
        }

        @Test
        @DisplayName("allows null oldStatus through the convenience factory too")
        void shouldAllowNullOldStatusViaFactory() {
            OrderEvent event = OrderEvent.of(orderId, null, OrderStatus.PENDING_VALIDATION);

            assertThat(event.oldStatus()).isNull();
        }

        @Test
        @DisplayName("still enforces newStatus non-null via the record's compact constructor")
        void shouldStillRejectNullNewStatus() {
            assertThatThrownBy(() -> OrderEvent.of(orderId, OrderStatus.PENDING_PAYMENT, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * FR-7 delivery is at-least-once (JVM-scoped, per OI-4). At-least-once
     * implies listeners may see the same event more than once, so equals()/
     * hashCode() correctness matters if any listener uses OrderEvent as a
     * dedup key (e.g. in a Set or as a map key for idempotent processing).
     */
    @Nested
    @DisplayName("equals() / hashCode()")
    class EqualityAndHashing {

        @Test
        @DisplayName("two events with identical field values are equal and share a hash code")
        void shouldBeEqualForIdenticalValues() {
            OrderEvent e1 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);
            OrderEvent e2 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);

            assertThat(e1).isEqualTo(e2);
            assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        }

        @Test
        @DisplayName("differs when orderId differs")
        void shouldDifferOnOrderId() {
            OrderEvent e1 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);
            OrderEvent e2 = new OrderEvent(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);

            assertThat(e1).isNotEqualTo(e2);
        }

        @Test
        @DisplayName("differs when newStatus differs")
        void shouldDifferOnNewStatus() {
            OrderEvent e1 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);
            OrderEvent e2 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_FAILED, now);

            assertThat(e1).isNotEqualTo(e2);
        }

        @Test
        @DisplayName("differs when oldStatus differs, including null vs non-null")
        void shouldDifferOnOldStatusIncludingNull() {
            OrderEvent withOld = new OrderEvent(orderId, OrderStatus.PENDING_VALIDATION,
                    OrderStatus.PENDING_PAYMENT, now);
            OrderEvent withoutOld = new OrderEvent(orderId, null, OrderStatus.PENDING_PAYMENT, now);

            assertThat(withOld).isNotEqualTo(withoutOld);
        }

        @Test
        @DisplayName("differs when occurredAt differs")
        void shouldDifferOnOccurredAt() {
            OrderEvent e1 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);
            OrderEvent e2 = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now.plusSeconds(1));

            assertThat(e1).isNotEqualTo(e2);
        }

        @Test
        @DisplayName("is not equal to null or a different type")
        void shouldNotBeEqualToNullOrDifferentType() {
            OrderEvent event = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);

            assertThat(event).isNotEqualTo(null);
            assertThat(event).isNotEqualTo("not an event");
        }
    }

    @Nested
    @DisplayName("toString() — debug/log readability only, no PII at this layer")
    class ToStringSanity {

        @Test
        @DisplayName("includes orderId and both statuses")
        void shouldContainKeyFieldsInToString() {
            OrderEvent event = new OrderEvent(orderId, OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAYMENT_AUTHORIZED, now);

            assertThat(event.toString())
                    .contains(orderId.toString())
                    .contains("PENDING_PAYMENT")
                    .contains("PAYMENT_AUTHORIZED");
        }
    }
}