package com.bowt.backend.orderprocessing.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AR-3: two independent thread pools with different backpressure policies.
 * <p>
 * orderProcessingExecutor  — general orchestration. CallerRunsPolicy gives implicit
 * backpressure: when the queue (1000) is full, the calling
 * (HTTP) thread runs the task itself rather than dropping it.
 * inventoryCheckExecutor   — short-lived per-product fan-out queries. AbortPolicy fails
 * fast under saturation; the caller catches the rejection and
 * transitions the order to INSUFFICIENT_INVENTORY.
 * <p>
 * [OI-7] These are complementary to RateLimitInterceptor at the HTTP edge, not duplicates.
 * Named ThreadFactory is deliberate — named threads show up in thread dumps / flame graphs
 * during Phase 3 profiling (async-profiler, JProfiler Monitor Usage view).
 */
@Configuration
public class ExecutorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfiguration.class);

    @Bean("orderProcessingExecutor")
    public ThreadPoolExecutor orderProcessingExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8, 16,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                namedThreadFactory("order-proc"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(false);
        log.info("orderProcessingExecutor started: core=8 max=16 queue=1000 policy=CallerRuns");
        return executor;
    }

    @Bean("inventoryCheckExecutor")
    public ThreadPoolExecutor inventoryCheckExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                namedThreadFactory("inv-check"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        log.info("inventoryCheckExecutor started: core=4 max=8 queue=500 policy=Abort");
        return executor;
    }

    /**
     * Dedicated pool for FR-7 async event dispatch — must not compete with order/inventory work.
     */
    @Bean("notificationExecutor")
    public ThreadPoolExecutor notificationExecutor() {
        return new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                namedThreadFactory("notify"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        };
    }
}