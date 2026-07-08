package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentRequest;
import com.bowt.backend.orderprocessing.application.port.out.PaymentGateway.PaymentResult;
import com.bowt.backend.orderprocessing.application.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.model.Money;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * TEST-1 (application layer) for {@link PaymentService}.
 * <p>
 * Mocks only the immediate out-port ({@link PaymentGateway}), per TEST-1 rules
 * in 06-testing.md — no Spring context loaded.
 * <p>
 * Coverage is organized around the single hardest correctness rule in FR-3:
 * a clean business decline must fail fast (no retry), while a transient
 * failure (timeout / gateway exception) must retry up to
 * {@code MAX_ATTEMPTS = 3} with exponential backoff (200ms, 400ms).
 * <p>
 * NOTE on the {@code RealTimeout} test: {@code PaymentService.TIMEOUT_SECONDS}
 * is a hardcoded {@code private static final long = 5}, so it cannot be
 * shortened for tests. That single test therefore costs real wall-clock time
 * (~15s: 3 attempts x 5s timeout, minus cancellation overhead, plus ~600ms of
 * backoff) and is tagged {@code "slow"} so it can be excluded from the fast
 * unit-test run and only executed in CI / on demand. If sub-second timeout
 * tests are wanted, {@code timeoutSeconds} should be injected via the
 * constructor instead of hardcoded — flagging that as a follow-up, not fixing
 * it silently here.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final PaymentRequest REQUEST =
            new PaymentRequest("ORDER-1", Money.of(100.00), PaymentMethod.CREDIT_CARD);

    @Mock
    private PaymentGateway paymentGateway;

    private ThreadPoolExecutor executor;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // Real executor (not a mock) — PaymentService's constructor requires a
        // concrete ThreadPoolExecutor, and part of what we're verifying is that
        // work actually runs on THIS pool rather than the common ForkJoinPool.
        executor = new ThreadPoolExecutor(
                2, 4, 60,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
        paymentService = new PaymentService(paymentGateway, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // Defensive: clear any interrupt flag left over from the interruption test
        // in case the test framework reuses this thread for the next test class.
        Thread.interrupted();
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Successful authorization")
    class SuccessfulAuthorization {

        @Test
        void returnsResultOnFirstAttempt_noRetry() {
            when(paymentGateway.authorize(REQUEST)).thenReturn(PaymentResult.success("TXN-1"));

            PaymentResult result = paymentService.authorize(REQUEST);

            assertThat(result.success()).isTrue();
            assertThat(result.transactionId()).isEqualTo("TXN-1");
            verify(paymentGateway, times(1)).authorize(REQUEST);
        }
    }

    // ── Business decline — the critical FR-3 rule ──────────────────────────

    @Nested
    @DisplayName("Business decline must NOT be retried")
    class BusinessDecline {

        @Test
        void throwsImmediately_singleAttemptOnly() {
            when(paymentGateway.authorize(REQUEST))
                    .thenReturn(PaymentResult.failure("INSUFFICIENT_FUNDS"));

            assertThatThrownBy(() -> paymentService.authorize(REQUEST))
                    .isInstanceOf(PaymentFailedException.class)
                    .hasMessageContaining("INSUFFICIENT_FUNDS");

            // This assertion is the whole point of the explicit retry loop
            // instead of @Retryable(on = Exception.class) — a clean decline
            // is not a transient failure and must not consume retry attempts.
            verify(paymentGateway, times(1)).authorize(REQUEST);
        }
    }

    // ── Transient failure via gateway exception (fast — no real timeout wait) ─

    @Nested
    @DisplayName("Transient gateway exception — retry with backoff")
    class TransientGatewayException {

        @Test
        void succeedsOnSecondAttempt_afterOneTransientFailure() {
            when(paymentGateway.authorize(REQUEST))
                    .thenThrow(new RuntimeException("connection reset"))
                    .thenReturn(PaymentResult.success("TXN-RETRY"));

            long start = System.nanoTime();
            PaymentResult result = paymentService.authorize(REQUEST);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(result.success()).isTrue();
            assertThat(result.transactionId()).isEqualTo("TXN-RETRY");
            verify(paymentGateway, times(2)).authorize(REQUEST);
            // One backoff of ~200ms (attempt 1) must have elapsed before the retry.
            assertThat(elapsedMs).isGreaterThanOrEqualTo(180);
        }

        @Test
        void exhaustsAllAttempts_thenThrowsPaymentFailedExceptionWithCause() {
            when(paymentGateway.authorize(REQUEST))
                    .thenThrow(new RuntimeException("gateway down"));

            assertThatThrownBy(() -> paymentService.authorize(REQUEST))
                    .isInstanceOf(PaymentFailedException.class)
                    .hasMessageContaining("Gateway unavailable after 3 attempts");

            verify(paymentGateway, times(3)).authorize(REQUEST);
        }

        @Test
        void appliesExponentialBackoffBetweenAttempts_200msThen400ms() {
            when(paymentGateway.authorize(REQUEST))
                    .thenThrow(new RuntimeException("fail-1"))
                    .thenThrow(new RuntimeException("fail-2"))
                    .thenThrow(new RuntimeException("fail-3"));

            long start = System.nanoTime();
            assertThatThrownBy(() -> paymentService.authorize(REQUEST))
                    .isInstanceOf(PaymentFailedException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // Backoff occurs after attempt 1 (200ms) and attempt 2 (400ms) only —
            // no sleep after the final (3rd) attempt. Lower bound with margin
            // for scheduling jitter; this is a correctness floor, not a
            // performance benchmark.
            assertThat(elapsedMs).isGreaterThanOrEqualTo(550);
        }
    }

    // ── Interruption ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Interruption while waiting on the gateway call")
    class Interruption {

        @Test
        void interruptedWhileWaiting_failsImmediately_restoresInterruptFlag() {
            // The gateway task itself doesn't matter here — the InterruptedException
            // is thrown by future.get() because the calling thread is already
            // marked interrupted, so this assertion is fast and deterministic
            // (no real sleep/timeout involved).
            lenient().when(paymentGateway.authorize(REQUEST))
                    .thenAnswer(invocation -> {
                        Thread.sleep(50);
                        return PaymentResult.success("SHOULD-NOT-BE-OBSERVED");
                    });

            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> paymentService.authorize(REQUEST))
                        .isInstanceOf(PaymentFailedException.class)
                        .hasMessageContaining("interrupted");

                // PaymentService re-interrupts the thread before throwing —
                // callers upstream must still observe the interrupt.
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted(); // clear flag so later tests aren't affected
            }
        }
    }

    // ── Real timeout path — slow, tagged separately ────────────────────────

    @Nested
    @DisplayName("Real timeout (TIMEOUT_SECONDS = 5, hardcoded) — slow")
    @Tag("slow")
    class RealTimeout {

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void timesOutOnEveryAttempt_thenFailsAfterExhaustion() {
            // Gateway call blocks well past the 5s authorization timeout on every
            // attempt, forcing PaymentService down the TimeoutException branch
            // (and exercising future.cancel(true), P8) three times.
            when(paymentGateway.authorize(REQUEST)).thenAnswer(invocation -> {
                Thread.sleep(8_000);
                return PaymentResult.success("UNREACHABLE");
            });

            assertThatThrownBy(() -> paymentService.authorize(REQUEST))
                    .isInstanceOf(PaymentFailedException.class)
                    .hasMessageContaining("Gateway unavailable after 3 attempts");

            verify(paymentGateway, times(3)).authorize(REQUEST);
        }
    }

    // ── Delegation ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("capture / refund delegate directly to the gateway")
    class Delegation {

        @Test
        void refundDelegatesToGateway() {
            Money amount = Money.of(49.99);

            paymentService.refund("TXN-1", amount);

            verify(paymentGateway).refund("TXN-1", amount);
            verifyNoMoreInteractions(paymentGateway);
        }

        @Test
        void captureDelegatesToGateway() {
            paymentService.capture("TXN-1");

            verify(paymentGateway).capture("TXN-1");
            verifyNoMoreInteractions(paymentGateway);
        }
    }

    // ── Executor wiring (P7 regression guard) ──────────────────────────────

    @Nested
    @DisplayName("Executor usage")
    class ExecutorUsage {

        @Test
        void authorizeRunsOnInjectedExecutor_notCommonForkJoinPool() {
            AtomicReference<String> threadName = new AtomicReference<>();
            when(paymentGateway.authorize(REQUEST)).thenAnswer(invocation -> {
                threadName.set(Thread.currentThread().getName());
                return PaymentResult.success("TXN-1");
            });

            paymentService.authorize(REQUEST);

            assertThat(threadName.get()).doesNotContain("ForkJoinPool.commonPool");
            assertThat(executor.getCompletedTaskCount()).isEqualTo(1);
        }
    }
}