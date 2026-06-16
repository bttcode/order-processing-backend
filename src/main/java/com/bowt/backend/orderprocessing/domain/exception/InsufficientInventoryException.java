package com.bowt.backend.orderprocessing.domain.exception;

public class InsufficientInventoryException extends RuntimeException {
    public InsufficientInventoryException(String productId, int requested) {
        super(String.format("Product %s has insufficient inventory, requested: %d", productId, requested));
    }
}
