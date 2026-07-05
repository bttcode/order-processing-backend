package com.bowt.backend.orderprocessing.domain.exception;

import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;

import java.util.UUID;

public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(UUID orderId, OrderStatus from, OrderStatus to) {
        super(String.format("Order %s cannot transition from %s to %s", orderId, from, to));
    }
}