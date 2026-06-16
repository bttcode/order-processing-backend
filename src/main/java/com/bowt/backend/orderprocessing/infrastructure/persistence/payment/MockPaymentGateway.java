package com.bowt.backend.orderprocessing.infrastructure.persistence.payment;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.domain.model.Money;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mock payment gateway used in all non-production profiles.
 * Phase 1: always succeeds. Phase 3: add configurable failure rate for testing.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult authorize(PaymentRequest request) {
        // TODO Phase 3: read a 'payment.mock.failure-rate' property to simulate failures
        String transactionId = "mock-txn-" + UUID.randomUUID();
        return new PaymentResult(true, transactionId, null);
    }

    @Override
    public void capture(String transactionId) {
        // No-op in mock
    }

    @Override
    public void refund(String transactionId, Money amount) {
        // No-op in mock — Phase 4 adds real refund tracking for cancellation
    }
}
