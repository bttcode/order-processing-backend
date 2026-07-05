package com.bowt.backend.orderprocessing.domain.exception;

public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String reason) {
        super("Payment failed: " + reason);
    }
}