package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentRequest;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentResult;
import com.bowt.backend.orderprocessing.application.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/**
 * FR-3: Payment Processing.
 * <p>
 * MOVED from domain/service to application/service (P2) — same rationale as
 * {@link InventoryService}: it owns I/O orchestration and executor wiring,
 * which are application concerns.
 * <p>
 * Fixes applied:
 * <ul>
 *   <li><b>P7</b> — {@code CompletableFuture.supplyAsync} now receives the
 *       explicit {@code orderProcessingExecutor} bean instead of defaulting
 *       to {@code ForkJoinPool.commonPool()}, which is shared JVM-wide and
 *       would let payment-gateway slowness starve unrelated parallel-stream
 *       work elsewhere in the JVM.</li>
 *   <li><b>P8</b> — {@code future.cancel(true)} on timeout is kept (it is
 *       still correct to attempt cancellation), but it is <i>not</i>
 *       sufficient by itself: interrupting a {@code CompletableFuture} does
 *       not guarantee the underlying HTTP socket read is interrupted. The
 *       real fix is a read-timeout configured on whatever HTTP client the
 *       concrete {@code PaymentGateway} adapter uses (e.g.
 *       {@code RestTemplate}/{@code WebClient} with a 5s read timeout) —
 *       that configuration lives in the infrastructure adapter, outside this
 *       file's reach, and is called out here so it isn't silently dropped.</li>
 * </ul>
 * <p>
 * Retry: FR-3 requires exponential backoff, max 3 attempts, on gateway
 * failure/timeout — implemented as an explicit loop rather than
 * {@code @Retryable} here. Reason: {@code @Retryable}'s default {@code on()}
 * is {@code Exception.class} (see [OI-9]/P10), and a business decline
 * ({@link PaymentFailedException} from a successfully-returned but
 * unsuccessful {@code PaymentResult}) must NOT be retried — only transient
 * failures (timeout, gateway exception) should be. An explicit loop keeps
 * that distinction obvious without relying on exact exception-type wiring in
 * the aspect.
 */
@Service
@Slf4j
public class PaymentService {

    private static final long TIMEOUT_SECONDS = 5; // FR-3 hard authorization timeout
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 200;

    private final PaymentGateway paymentGateway;
    private final ThreadPoolExecutor orderProcessingExecutor;

    public PaymentService(
            PaymentGateway paymentGateway,
            @Qualifier("orderProcessingExecutor") ThreadPoolExecutor orderProcessingExecutor) {
        this.paymentGateway = paymentGateway;
        this.orderProcessingExecutor = orderProcessingExecutor;
    }

    /**
     * Authorizes payment, retrying only on transient failure (timeout /
     * gateway exception), never on a clean business decline.
     */
    public PaymentResult authorize(PaymentRequest request) {
        Exception lastTransientFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            CompletableFuture<PaymentResult> future =
                    CompletableFuture.supplyAsync(() -> paymentGateway.authorize(request), orderProcessingExecutor);

            try {
                PaymentResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (result.success()) {
                    return result;
                }
                // Clean business decline — do not retry, fail immediately (FR-3).
                throw new PaymentFailedException(result.failureReason());

            } catch (TimeoutException e) {
                future.cancel(true); // P8 — best-effort only, see class javadoc
                lastTransientFailure = e;
                log.warn("Payment authorization attempt {}/{} timed out after {}s for order {}",
                        attempt, MAX_ATTEMPTS, TIMEOUT_SECONDS, request.orderId());
            } catch (ExecutionException e) {
                lastTransientFailure = e;
                log.warn("Payment authorization attempt {}/{} failed for order {}: {}",
                        attempt, MAX_ATTEMPTS, request.orderId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PaymentFailedException("Payment authorization interrupted");
            }

            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }

        throw new PaymentFailedException(
                "Gateway unavailable after " + MAX_ATTEMPTS + " attempts"
                        + ": " + lastTransientFailure.getMessage());
    }

    public void refund(String transactionId, Money amount) {
        paymentGateway.refund(transactionId, amount);
    }

    public void capture(String transactionId) {
        paymentGateway.capture(transactionId);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF_MS * (1L << (attempt - 1))); // exponential: 200ms, 400ms, ...
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}