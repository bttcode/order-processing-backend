package com.bowt.backend.orderprocessing.domain.annotation;

import java.lang.annotation.*;

/**
 * Marks a method for automatic retry on the listed exception types.
 *
 * <p>Applied by {@code RetryAspect} (infrastructure layer) — this annotation
 * lives in {@code domain/annotation/} so it carries zero framework imports,
 * keeping the domain dependency-free while still being reachable by the
 * {@code @Around} advice.
 *
 * <p><b>Important [OI-9]:</b> {@code RetryAspect} uses {@code Thread.sleep()}
 * which blocks the carrier thread. Annotate only synchronous service methods.
 * Reactive call-sites must use {@code Mono.retryWhen()} or Resilience4j
 * {@code RetryOperator} instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Retryable {

    /**
     * Exception types that trigger a retry.  Defaults to any {@link Exception}.
     */
    Class<? extends Exception>[] on() default {Exception.class};

    /**
     * Maximum total attempts (first call + retries).
     */
    int maxAttempts() default 3;

    /**
     * Base delay in milliseconds between attempts.
     * The actual pause is {@code delayMs * attemptNumber} (linear backoff
     * shown here; swap for exponential in production as noted in AR-5).
     */
    long delayMs() default 100;
}
