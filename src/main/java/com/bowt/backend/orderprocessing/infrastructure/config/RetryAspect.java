package com.bowt.backend.orderprocessing.infrastructure.config;

import com.bowt.backend.orderprocessing.domain.annotation.Retryable;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AR-5 / ADR-003. Synchronous retry ONLY.
 * <p>
 * [OI-9] Thread.sleep() below blocks the carrier thread. This aspect must never be applied
 * to a method invoked from OrderService.processOrderReactive() (the Mono pipeline) — doing
 * so would park a Reactor / boundedElastic thread for the full backoff duration and defeat
 * the non-blocking model. Enforcement is a code-review gate, not a compile-time check:
 * grep for @Retryable usages and confirm none sit on the reactive call graph before merging.
 * <p>
 * [P10 fix] Retryable.on() defaults to Exception.class. Without the explicit type check below,
 * every exception (including InsufficientInventoryException, which must never be retried)
 * would be retried. isAssignableFrom also correctly matches Hibernate's StaleObjectStateException,
 * a subclass of jakarta.persistence.OptimisticLockException.
 */
@Aspect
@Component
@Slf4j
public class RetryAspect {

    @Around("@annotation(retryable)")
    public Object retry(ProceedingJoinPoint pjp, Retryable retryable) throws Throwable {
        int maxAttempts = retryable.maxAttempts();
        long delayMs = retryable.delayMs();
        Class<? extends Throwable>[] retryOn = retryable.on();

        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return pjp.proceed();
            } catch (Throwable ex) {
                lastFailure = ex;
                boolean shouldRetry = Arrays.stream(retryOn)
                        .anyMatch(type -> type.isAssignableFrom(ex.getClass()));

                if (!shouldRetry || attempt == maxAttempts) {
                    if (!shouldRetry) {
                        log.debug("Not retrying {} — not in declared retry set {}",
                                ex.getClass().getSimpleName(), Arrays.toString(retryOn));
                    }
                    throw ex;
                }

                log.warn("Retry attempt {}/{} for {} after {}", attempt, maxAttempts,
                        pjp.getSignature().toShortString(), ex.getClass().getSimpleName());
                Thread.sleep(delayMs * attempt); // linear backoff — synchronous path only, see class Javadoc
            }
        }
        // Unreachable — loop always returns or throws — but keep the compiler happy.
        throw new IllegalStateException("RetryAspect exhausted without resolution", lastFailure);
    }
}