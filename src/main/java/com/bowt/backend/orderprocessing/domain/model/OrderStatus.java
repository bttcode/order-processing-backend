package com.bowt.backend.orderprocessing.domain.model;

public enum OrderStatus {
    PENDING_VALIDATION,
    PENDING_PAYMENT,
    PAYMENT_AUTHORIZED,
    CONFIRMED,
    CANCELLED,
    INSUFFICIENT_INVENTORY,
    PAYMENT_FAILED;

    // Valid forward transitions — enforced by Order.transitionTo()
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING_VALIDATION -> next == PENDING_PAYMENT || next == INSUFFICIENT_INVENTORY;
            case PENDING_PAYMENT -> next == PAYMENT_AUTHORIZED || next == PAYMENT_FAILED;
            case PAYMENT_AUTHORIZED -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == CANCELLED;
            // Terminal states — no further transitions
            case INSUFFICIENT_INVENTORY, PAYMENT_FAILED, CANCELLED -> false;
        };
    }
}
