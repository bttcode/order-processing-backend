package com.bowt.backend.orderprocessing.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dedicated executor is used here rather than Spring's default
 * {@code SimpleAsyncTaskExecutor} (which creates an unbounded number of raw
 * threads — a footgun under the load profile this system targets).
 */
@Configuration
@EnableAsync
public class AsyncConfiguration implements AsyncConfigurer {

    @Override
    @Bean(name = "eventDispatchExecutor")
    public Executor getAsyncExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "event-dispatch-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        // Bounded — a burst of order events must not spawn unbounded threads.
        return Executors.newFixedThreadPool(4, threadFactory);
    }
}