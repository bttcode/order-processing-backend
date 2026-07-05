package com.bowt.backend.orderprocessing.infrastructure.payment;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.domain.model.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [I-5 fix] Was located under infrastructure/persistence/payment/ — wrong per TR-3
 * (persistence/ should contain only database infrastructure). Payment adapters belong
 * directly under infrastructure/payment/. Moved here.
 * <p>
 * [Phase 3 prerequisite] Injects a configurable failure rate so load tests can exercise
 * the retry (FR-3) and circuit-breaker (NFR-3) paths on demand — a gateway that always
 * succeeds never exercises those code paths, so profiling them would test nothing.
 * Default 0.0 (always succeeds) to keep Phase 1/2 integration tests deterministic.
 */
@Component
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    private final double failureRate;
    private final Map<String, String> capturedTransactions = new ConcurrentHashMap<>();

    public MockPaymentGateway(@Value("${payment.mock.failure-rate:0.0}") double failureRate) {
        this.failureRate = failureRate;
    }

    @Override
    public PaymentResult authorize(PaymentRequest request) {
        // Simulate gateway latency so PaymentService's 5s timeout path is exercisable.
        simulateLatency();

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            log.warn("MockPaymentGateway: simulated authorization failure for order {}", request.orderId());
            return new PaymentResult(false, null,
                    "Simulated gateway decline (failure-rate injection)");
        }

        String transactionId = "txn_" + UUID.randomUUID();
        capturedTransactions.put(transactionId, request.orderId());
        return new PaymentResult(true, transactionId, null);
    }

    @Override
    public void capture(String transactionId) {
        if (!capturedTransactions.containsKey(transactionId)) {
            throw new IllegalStateException("Unknown transaction id for capture: " + transactionId);
        }
        log.info("MockPaymentGateway: captured {}", transactionId);
    }

    @Override
    public void refund(String transactionId, Money amount) {
        log.info("MockPaymentGateway: refunded {} on transaction {}", amount, transactionId);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(5, 40));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}