package com.bowt.backend.orderprocessing.application.exception;

public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String reason) {
        super("Payment failed: " + reason);
    }
}