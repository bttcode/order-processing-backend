package com.bowt.backend.orderprocessing.domain.service;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentRequest;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentResult;
import com.bowt.backend.orderprocessing.domain.annotation.Retryable;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 2: payment authorization with retry and 5-second timeout.
 *
 * <h3>Design (FR-3)</h3>
 * <ul>
 *   <li>Delegates to {@link PaymentGateway} (port interface — no provider coupling).</li>
 *   <li>{@link #authorize} is annotated with {@code @Retryable} so
 *       {@code RetryAspect} wraps it with up to 3 attempts and linear back-off.</li>
 *   <li>Each attempt enforces a 5-second timeout via a bounded
 *       {@code CompletableFuture.get(5, SECONDS)} call. Timeout is treated as
 *       a failure — the exception bubbles to {@code RetryAspect} for the next
 *       attempt.</li>
 *   <li>After 3 exhausted attempts, {@link PaymentFailedException} propagates
 *       to {@code OrderService}, which releases inventory (BR-6).</li>
 * </ul>
 *
 * <p><b>[OI-9]</b> {@code @Retryable} uses Thread.sleep inside RetryAspect.
 * This class is synchronous — it must not be called from within the reactive
 * pipeline without wrapping in {@code Mono.fromCallable(...).subscribeOn(...)}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private static final long TIMEOUT_SECONDS = 5L;

    private final PaymentGateway paymentGateway;

    /**
     * Authorizes payment for the given order.
     *
     * <p>Throws {@link PaymentFailedException} on gateway rejection or timeout;
     * {@code RetryAspect} retries on any {@link Exception} up to 3 times.
     *
     * @return the gateway's {@link PaymentResult} on success
     */
    @Retryable(maxAttempts = 3, delayMs = 200)
    public PaymentResult authorize(Order order) {
        log.info("Authorizing payment for order {} (method={})",
                order.getId(), order.getPaymentMethod());

        PaymentRequest req = new PaymentRequest(
                order.getId().toString(),
                order.getTotalAmount(),
                order.getPaymentMethod()
        );

        // Enforce 5-second timeout per FR-3
        CompletableFuture<PaymentResult> future =
                CompletableFuture.supplyAsync(() -> paymentGateway.authorize(req));

        PaymentResult result;
        try {
            result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new PaymentFailedException("Gateway timed out after " + TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentFailedException("Payment authorization interrupted");
        } catch (ExecutionException e) {
            throw new PaymentFailedException("Gateway error: " + e.getCause().getMessage());
        }

        if (!result.success()) {
            throw new PaymentFailedException(result.failureReason());
        }

        log.info("Payment authorized for order {} — txn={}",
                order.getId(), result.transactionId());
        return result;
    }

    /**
     * Captures a previously authorized transaction (no-op in mock).
     */
    public void capture(String transactionId) {
        paymentGateway.capture(transactionId);
        log.info("Payment captured: txn={}", transactionId);
    }

    /**
     * Issues a refund for cancellation (FR-6).
     */
    public void refund(String transactionId, Money amount) {
        paymentGateway.refund(transactionId, amount);
        log.info("Refund issued: txn={}, amount={}", transactionId, amount);
    }
}