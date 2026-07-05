package com.bowt.backend.orderprocessing.domain.event;

import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OrderEvent(
        UUID orderId,
        OrderStatus oldStatus,
        OrderStatus newStatus,
        Instant occurredAt
) {
    public OrderEvent {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        // oldStatus is intentionally nullable — the very first transition
        // (order creation into PENDING_VALIDATION) has no prior status.
    }

    /** Convenience factory stamping {@code occurredAt} with the current instant. */
    public static OrderEvent of(UUID orderId, OrderStatus oldStatus, OrderStatus newStatus) {
        return new OrderEvent(orderId, oldStatus, newStatus, Instant.now());
    }
}