package com.bowt.backend.orderprocessing.infrastructure.config;

import com.bowt.backend.orderprocessing.domain.annotation.Retryable;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP retry aspect — wraps any method annotated with {@link Retryable}.
 *
 * <p><b>[OI-9] Threading constraint:</b> {@code Thread.sleep()} blocks the
 * carrier thread. This aspect is therefore intentionally restricted to
 * <em>synchronous</em> service methods (e.g. {@code InventoryService.reserveInventory},
 * {@code PaymentService.authorize}). The reactive pipeline in
 * {@code OrderService.processOrderReactive} must NOT route through this aspect
 * — it uses {@code Mono.retryWhen()} directly.
 *
 * <p>Retry condition: the thrown exception must be assignment-compatible with
 * at least one of the types declared in {@link Retryable#on()}.
 */
@Aspect
@Component
@Slf4j
public class RetryAspect {

    @Around("@annotation(retryable)")
    public Object retry(ProceedingJoinPoint pjp, Retryable retryable) throws Throwable {
        int maxAttempts = retryable.maxAttempts();
        long delayMs = retryable.delayMs();
        Class<? extends Exception>[] retryOn = retryable.on();

        String methodName = pjp.getSignature().toShortString();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return pjp.proceed();
            } catch (Exception ex) {
                boolean shouldRetry = Arrays.stream(retryOn)
                        .anyMatch(type -> type.isAssignableFrom(ex.getClass()));

                if (!shouldRetry || attempt == maxAttempts) {
                    log.warn("[RetryAspect] {}: giving up after {}/{} attempts — {}",
                            methodName, attempt, maxAttempts, ex.getMessage());
                    throw ex;
                }

                long pause = delayMs * attempt;   // linear back-off
                log.warn("[RetryAspect] {}: attempt {}/{} failed ({}), retrying in {} ms",
                        methodName, attempt, maxAttempts, ex.getClass().getSimpleName(), pause);
                Thread.sleep(pause);
            }
        }
        // Unreachable: the loop always throws or returns before reaching here.
        throw new IllegalStateException("RetryAspect: unreachable code path");
    }
}