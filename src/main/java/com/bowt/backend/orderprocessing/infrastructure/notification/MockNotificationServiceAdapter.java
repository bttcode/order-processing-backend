package com.bowt.backend.orderprocessing.infrastructure.notification;

import com.bowt.backend.orderprocessing.application.exception.NotificationDeliveryException;
import com.bowt.backend.orderprocessing.application.port.out.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock implementation of the {@link NotificationService} outgoing port.
 * <p>
 * Real email/SMS/webhook providers are out of scope for this project
 * (see {@code 00-overview.md § Out of Scope}) — this adapter simulates an
 * external channel call closely enough to exercise the behaviours that
 * actually matter for the learning objectives:
 * <ul>
 *   <li>Non-trivial latency, so callers cannot assume this is free/instant
 *       (relevant because listeners already run off the order-pipeline
 *       thread via {@code notificationExecutor} — see AR-2 Observer +
 *       EventNotificationAdapter).</li>
 *   <li>A configurable transient failure rate, so the {@code NFR-3}
 *       isolation guarantee ("a broken listener must never fail the main
 *       order pipeline, and one listener throwing must not stop the
 *       others") is actually exercised by tests, not just assumed.</li>
 *   <li>PII-safe logging per {@code NFR-5}: email/SMS recipients are never
 *       logged in full at INFO or above.</li>
 * </ul>
 * Failure/latency are constructor parameters (not only {@code @Value}
 * fields) specifically so plain-JUnit infrastructure tests can set
 * {@code failureRatePercent=0} or {@code =100} to deterministically test
 * both paths without a Spring context.
 */
@Component
@Slf4j
public class MockNotificationServiceAdapter implements NotificationService {

    /**
     * Channels whose recipientId is considered PII and must be masked in logs.
     */
    private static final Set<String> PII_CHANNELS = Set.of("email", "sms");

    /**
     * Channels this mock adapter recognizes; anything else is still delivered
     * but flagged, so a typo in a listener's channel string is visible.
     */
    private static final Set<String> KNOWN_CHANNELS =
            Set.of("email", "sms", "warehouse-webhook", "accounting-export");

    private final MeterRegistry meterRegistry;
    private final int failureRatePercent;
    private final int minLatencyMillis;
    private final int maxLatencyMillis;

    public MockNotificationServiceAdapter(
            MeterRegistry meterRegistry,
            @Value("${notification.mock.failure-rate-percent:2}") int failureRatePercent,
            @Value("${notification.mock.min-latency-ms:10}") int minLatencyMillis,
            @Value("${notification.mock.max-latency-ms:40}") int maxLatencyMillis) {
        if (failureRatePercent < 0 || failureRatePercent > 100) {
            throw new IllegalArgumentException("failureRatePercent must be between 0 and 100");
        }
        if (minLatencyMillis < 0 || maxLatencyMillis < minLatencyMillis) {
            throw new IllegalArgumentException("invalid latency bounds: min=" + minLatencyMillis
                    + " max=" + maxLatencyMillis);
        }
        this.meterRegistry = meterRegistry;
        this.failureRatePercent = failureRatePercent;
        this.minLatencyMillis = minLatencyMillis;
        this.maxLatencyMillis = maxLatencyMillis;
    }

    @Override
    public void send(NotificationRequest request) {
        validate(request);

        if (!KNOWN_CHANNELS.contains(request.channel())) {
            log.warn("Notification channel '{}' is not a recognised mock channel — "
                    + "delivering anyway (recipient={})", request.channel(), maskIfPii(request));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            simulateNetworkLatency();
            simulateTransientFailure(request);

            log.info("Notification delivered: channel={} recipient={} subject=\"{}\"",
                    request.channel(), maskIfPii(request), request.subject());
            // Full message body only at DEBUG — disabled in production per NFR-5 log policy.
            log.debug("Notification body (recipient={}): {}", request.recipientId(), request.message());

            meterRegistry.counter(
                    "notifications_delivered_total", "channel", request.channel()).increment();
        } catch (NotificationDeliveryException ex) {
            meterRegistry.counter(
                    "notifications_failed_total", "channel", request.channel()).increment();
            log.error("Notification delivery FAILED: channel={} recipient={} reason={}",
                    request.channel(), maskIfPii(request), ex.getMessage());
            throw ex;
        } finally {
            sample.stop(meterRegistry.timer("notification_delivery_duration_seconds",
                    "channel", request.channel()));
        }
    }

    private void validate(NotificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (isBlank(request.recipientId())) {
            throw new IllegalArgumentException("recipientId must not be blank");
        }
        if (isBlank(request.channel())) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (isBlank(request.subject())) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (isBlank(request.message())) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void simulateNetworkLatency() {
        if (maxLatencyMillis == 0) {
            return;
        }
        int delay = ThreadLocalRandom.current().nextInt(minLatencyMillis, maxLatencyMillis + 1);
        if (delay == 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException(
                    "Interrupted while simulating notification channel latency", e);
        }
    }

    private void simulateTransientFailure(NotificationRequest request) {
        if (failureRatePercent > 0
                && ThreadLocalRandom.current().nextInt(100) < failureRatePercent) {
            throw new NotificationDeliveryException(request.channel(), request.recipientId());
        }
    }

    private String maskIfPii(NotificationRequest request) {
        if (!PII_CHANNELS.contains(request.channel())) {
            return request.recipientId();
        }
        return maskPii(request.recipientId());
    }

    /**
     * Package-private (not private) so infrastructure unit tests can verify
     * masking behaviour directly without reflection.
     */
    String maskPii(String value) {
        if (value == null || value.isBlank()) {
            return "***";
        }
        int at = value.indexOf('@');
        if (at > 0) {
            // email-like: keep first char + domain, mask local part
            return value.charAt(0) + "***@" + value.substring(at + 1);
        }
        if (value.length() > 4) {
            // phone-like: keep last 4 digits only, matching the credit-card masking
            // convention already established in NFR-5.
            return "***" + value.substring(value.length() - 4);
        }
        return "***";
    }
}