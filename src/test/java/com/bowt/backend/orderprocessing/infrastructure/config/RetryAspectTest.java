package com.bowt.backend.orderprocessing.infrastructure.config;

import com.bowt.backend.orderprocessing.domain.annotation.Retryable;
import jakarta.persistence.OptimisticLockException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RetryAspect}.
 *
 * <p>These tests call {@code retryAspect.retry(pjp, retryable)} directly rather than
 * weaving AspectJ, since the behaviour under test lives entirely inside that method.
 * The {@link Retryable} annotation instances are real (pulled via reflection off the
 * helper methods below), not hand-mocked, so any change to the annotation's actual
 * attribute defaults will surface here rather than being silently masked.
 *
 * <p>Assumption: {@code Retryable.on()} defaults to {@code { Exception.class }} per the
 * class-level Javadoc of {@link RetryAspect} describing the P10 fix. If the real
 * declaration differs, adjust {@link #methodWithDefaultRetry()} accordingly.
 */
class RetryAspectTest {

    private RetryAspect retryAspect;
    private ProceedingJoinPoint pjp;

    @BeforeEach
    void setUp() {
        retryAspect = new RetryAspect();
        pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("InventoryService.reserveInventory(..)");
    }

    @AfterEach
    void clearInterruptFlag() {
        // Defensive: a failed interrupt-propagation test must not bleed into the next test
        Thread.interrupted();
    }

    // ── Helper methods — annotation source only, never invoked directly ────────

    @Retryable(maxAttempts = 3, delayMs = 10, on = {OptimisticLockException.class})
    void methodWithOptimisticLockRetry() {
    }

    @Retryable(maxAttempts = 1, delayMs = 10, on = {OptimisticLockException.class})
    void methodWithSingleAttempt() {
    }

    @Retryable(maxAttempts = 3, delayMs = 10)
    void methodWithDefaultRetry() {
    }

    private Retryable annotationOf(String methodName) throws NoSuchMethodException {
        Method m = RetryAspectTest.class.getDeclaredMethod(methodName);
        return m.getAnnotation(Retryable.class);
    }

    // ── 1. Happy path ────────────────────────────────────────────────────────

    @Test
    void returnsResultImmediately_whenNoExceptionThrown() throws Throwable {
        Retryable retryable = annotationOf("methodWithOptimisticLockRetry");
        when(pjp.proceed()).thenReturn("success");

        Object result = retryAspect.retry(pjp, retryable);

        assertThat(result).isEqualTo("success");
        verify(pjp, times(1)).proceed();
    }

    // ── 2. Retries and recovers on a matching exception ─────────────────────

    @Test
    void retriesAndSucceeds_whenExceptionMatchesRetryOnSet() throws Throwable {
        Retryable retryable = annotationOf("methodWithOptimisticLockRetry");
        when(pjp.proceed())
                .thenThrow(new OptimisticLockException("conflict"))
                .thenReturn("recovered");

        Object result = retryAspect.retry(pjp, retryable);

        assertThat(result).isEqualTo("recovered");
        verify(pjp, times(2)).proceed();
    }

    // ── 3. Subclass matching via isAssignableFrom (the P10 fix under test) ──

    @Test
    void retries_whenThrownExceptionIsSubclassOfDeclaredType() throws Throwable {
        Retryable retryable = annotationOf("methodWithDefaultRetry");
        when(pjp.proceed())
                .thenThrow(new StaleObjectStateException("products", "PROD-1"))
                .thenReturn("recovered");

        Object result = retryAspect.retry(pjp, retryable);

        assertThat(result).isEqualTo("recovered");
        verify(pjp, times(2)).proceed();
    }

    // ── 4. Fail-fast on a non-matching exception — the core P10 regression ──

    @Test
    void doesNotRetry_whenExceptionNotInDeclaredSet() throws Throwable {
        Retryable retryable = annotationOf("methodWithOptimisticLockRetry"); // on = {OptimisticLockException}
        IllegalStateException ex = new IllegalStateException(
                "not retryable, e.g. InsufficientInventoryException-like");
        when(pjp.proceed()).thenThrow(ex);

        assertThatThrownBy(() -> retryAspect.retry(pjp, retryable))
                .isSameAs(ex);

        verify(pjp, times(1)).proceed(); // no retry attempted
    }

    // ── 5. Exhaustion — rethrows the ORIGINAL last exception, not a wrapper ──

    @Test
    void exhaustsMaxAttemptsAndRethrowsLastFailure() throws Throwable {
        Retryable retryable = annotationOf("methodWithOptimisticLockRetry"); // maxAttempts = 3
        OptimisticLockException ex = new OptimisticLockException("still conflicting");
        when(pjp.proceed()).thenThrow(ex);

        assertThatThrownBy(() -> retryAspect.retry(pjp, retryable))
                .isSameAs(ex);

        verify(pjp, times(3)).proceed();
    }

    @Test
    void singleAttempt_throwsImmediatelyWithoutRetrying() throws Throwable {
        Retryable retryable = annotationOf("methodWithSingleAttempt"); // maxAttempts = 1
        OptimisticLockException ex = new OptimisticLockException("conflict");
        when(pjp.proceed()).thenThrow(ex);

        assertThatThrownBy(() -> retryAspect.retry(pjp, retryable))
                .isSameAs(ex);

        verify(pjp, times(1)).proceed();
    }

    // ── 6. Linear backoff timing: delayMs * attempt between tries ───────────

    @Test
    void appliesLinearBackoff_betweenRetryAttempts() throws Throwable {
        Retryable retryable = annotationOf("methodWithOptimisticLockRetry"); // delayMs=10, maxAttempts=3
        when(pjp.proceed())
                .thenThrow(new OptimisticLockException("c1"))
                .thenThrow(new OptimisticLockException("c2"))
                .thenReturn("ok");

        long startNanos = System.nanoTime();
        Object result = retryAspect.retry(pjp, retryable);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(result).isEqualTo("ok");
        // sleep(10*1) after attempt 1, sleep(10*2) after attempt 2 -> >= 30ms floor.
        // No sleep after the final (successful) attempt.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(30L);
    }

    // ── 7. Default retry-on-anything behaviour (P10 default) ────────────────

    @Test
    void defaultOnSet_retriesGenericExceptions() throws Throwable {
        Retryable retryable = annotationOf("methodWithDefaultRetry");
        when(pjp.proceed())
                .thenThrow(new RuntimeException("transient failure"))
                .thenReturn("ok");

        Object result = retryAspect.retry(pjp, retryable);

        assertThat(result).isEqualTo("ok");
        verify(pjp, times(2)).proceed();
    }

    // ── 8. Interrupt during backoff must propagate, not be swallowed ────────

    @Test
    void propagatesInterruptedException_whenSleepIsInterrupted() throws Throwable {
        Retryable retryable = annotationOf("methodWithOptimisticLockRetry");
        when(pjp.proceed()).thenThrow(new OptimisticLockException("c1"));

        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> retryAspect.retry(pjp, retryable))
                .isInstanceOf(InterruptedException.class);
    }
}