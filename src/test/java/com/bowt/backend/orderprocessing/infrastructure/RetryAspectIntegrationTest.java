package com.bowt.backend.orderprocessing.infrastructure;

import com.bowt.backend.orderprocessing.domain.annotation.Retryable;
import com.bowt.backend.orderprocessing.infrastructure.config.RetryAspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link RetryAspect}.
 *
 * <p>Verifies that a method annotated {@code @Retryable(maxAttempts=3)} is
 * called exactly 3 times when it keeps failing, and that a method which
 * succeeds on the 2nd attempt is called exactly 2 times.
 */
@SpringBootTest(classes = RetryAspectIntegrationTest.TestConfig.class)
@Testcontainers
class RetryAspectIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");
    @Autowired
    FailingService failingService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void retryable_exhaustsAllAttempts_thenThrows() {
        failingService.resetCallCount();

        assertThatThrownBy(() -> failingService.alwaysFails())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Always fails");

        assertThat(failingService.getCallCount())
                .as("Must be called exactly maxAttempts=3 times")
                .isEqualTo(3);
    }

    @Test
    void retryable_succeedsOnSecondAttempt_callsExactlyTwice() {
        failingService.resetCallCount();

        String result = failingService.failsOnceThenSucceeds();

        assertThat(result).isEqualTo("ok");
        assertThat(failingService.getCallCount())
                .as("Must be called exactly 2 times (1 failure + 1 success)")
                .isEqualTo(2);
    }

    @Test
    void retryable_exceptionNotInOnList_doesNotRetry() {
        failingService.resetCallCount();

        // IllegalArgumentException is not in on={IllegalStateException.class}
        assertThatThrownBy(() -> failingService.throwsWrongExceptionType())
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(failingService.getCallCount())
                .as("Should not retry when exception type does not match @Retryable.on()")
                .isEqualTo(1);
    }

    // ── Inner test infrastructure ──────────────────────────────────────────

    @TestConfiguration
    @EnableAspectJAutoProxy
    @Import(RetryAspect.class)
    static class TestConfig {
        @Bean
        FailingService failingService() {
            return new FailingService();
        }
    }

    static class FailingService {

        private final AtomicInteger callCount = new AtomicInteger();

        void resetCallCount() {
            callCount.set(0);
        }

        int getCallCount() {
            return callCount.get();
        }

        @Retryable(on = IllegalStateException.class, maxAttempts = 3, delayMs = 10)
        public void alwaysFails() {
            callCount.incrementAndGet();
            throw new IllegalStateException("Always fails");
        }

        @Retryable(on = IllegalStateException.class, maxAttempts = 3, delayMs = 10)
        public String failsOnceThenSucceeds() {
            int call = callCount.incrementAndGet();
            if (call == 1) throw new IllegalStateException("First attempt fails");
            return "ok";
        }

        @Retryable(on = IllegalStateException.class, maxAttempts = 3, delayMs = 10)
        public void throwsWrongExceptionType() {
            callCount.incrementAndGet();
            throw new IllegalArgumentException("Wrong type — should not retry");
        }
    }
}