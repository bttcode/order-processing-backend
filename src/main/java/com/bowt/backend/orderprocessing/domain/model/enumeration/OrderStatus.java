package com.bowt.backend.orderprocessing.domain.model.enumeration;

public enum OrderStatus {
    PENDING_VALIDATION,
    PENDING_PAYMENT,
    PAYMENT_AUTHORIZED,
    CONFIRMED,
    CANCELLED,
    INSUFFICIENT_INVENTORY,
    PAYMENT_FAILED;

    // Valid forward transitions — enforced by Order.transitionTo()
    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null) {
            return false;
        }

        return switch (from) {
            case PENDING_VALIDATION -> to == PENDING_PAYMENT || to == INSUFFICIENT_INVENTORY;
            case PENDING_PAYMENT -> to == PAYMENT_AUTHORIZED || to == PAYMENT_FAILED;
            case PAYMENT_AUTHORIZED -> to == CONFIRMED || to == CANCELLED;
            case CONFIRMED -> to == CANCELLED;
            // Terminal states — no further transitions
            case INSUFFICIENT_INVENTORY, PAYMENT_FAILED, CANCELLED -> false;
        };
    }
}
