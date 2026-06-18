package com.bowt.backend.orderprocessing.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thread pool configuration (AR-3).
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>orderProcessingExecutor</b> — {@code CallerRunsPolicy}: when the
 *       queue is full the HTTP thread itself executes the task, providing
 *       implicit backpressure. No orders are dropped; the caller just blocks.
 *       This pairs with the {@code RateLimitInterceptor} (API-4) at the HTTP
 *       edge — the rate limiter rejects early, the policy throttles deeper.</li>
 *   <li><b>inventoryCheckExecutor</b> — {@code AbortPolicy}: inventory checks
 *       are short-lived parallel fan-outs via {@code CompletableFuture.allOf()}.
 *       Overflow means the system is saturated; failing fast is safer than
 *       queuing indefinitely, since the caller retries the whole order anyway
 *       (optimistic lock retry path).</li>
 * </ul>
 *
 * <p>[OI-7] These two mechanisms are complementary: rate limiter rejects at
 * the HTTP edge; CallerRunsPolicy throttles at the executor boundary. Both
 * must be exercised under load (TEST-3 Scenario 4).
 */
@Configuration
@Slf4j
public class ExecutorConfiguration {

    /**
     * General order-processing work (orchestration, save calls).
     * core=8, max=16, queue=1000, CallerRunsPolicy.
     */
    @Bean("orderProcessingExecutor")
    public ThreadPoolExecutor orderProcessingExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8,
                16,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("order-proc-" + t.threadId());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        log.info("orderProcessingExecutor initialised: core={}, max={}, queue=1000",
                executor.getCorePoolSize(), executor.getMaximumPoolSize());
        return executor;
    }

    /**
     * Parallel inventory fan-out (CompletableFuture.allOf per order).
     * core=4, max=8, queue=500, AbortPolicy — fail fast on saturation.
     */
    @Bean("inventoryCheckExecutor")
    public ThreadPoolExecutor inventoryCheckExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4,
                8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("inv-check-" + t.threadId());
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        log.info("inventoryCheckExecutor initialised: core={}, max={}, queue=500",
                executor.getCorePoolSize(), executor.getMaximumPoolSize());
        return executor;
    }
}