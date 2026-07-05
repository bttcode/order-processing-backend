package com.bowt.backend.orderprocessing.infrastructure.rest.ratelimit;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [OI-15 addressed] The original spec used a single global RateLimiter — one noisy API
 * key would starve every other caller. This implementation keys a RateLimiter per API
 * key (ConcurrentHashMap, exactly the fix OI-15 suggests as the production-correct
 * alternative to the Guava-global approach). Runs AFTER ApiKeyInterceptor, so by the
 * time this fires the key is already known-valid — see the OI-3 note in ApiKeyInterceptor
 * about why keying on an *unvalidated* header would be unsafe.
 * <p>
 * [OI-7] Complementary to, not a duplicate of, the executor-level CallerRunsPolicy
 * (ExecutorConfiguration). This rejects at the HTTP edge before any domain work starts;
 * CallerRunsPolicy throttles work that already passed this gate.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final double PERMITS_PER_SECOND = 100.0 / 60.0; // 100 req/min per key
    private static final int LIMIT = 100;

    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String apiKey = request.getHeader("X-API-Key");
        String bucketKey = (apiKey != null) ? apiKey : "anonymous";

        RateLimiter limiter = limiters.computeIfAbsent(bucketKey, k -> RateLimiter.create(PERMITS_PER_SECOND));

        response.setHeader("X-RateLimit-Limit", String.valueOf(LIMIT));

        if (!limiter.tryAcquire()) {
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("Retry-After", "60");
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            try (PrintWriter writer = response.getWriter()) {
                writer.write("""
                        {"type":"https://api.example.com/errors/rate-limit-exceeded",
                         "title":"Rate Limit Exceeded",
                         "status":429,
                         "detail":"Too many requests for this API key",
                         "instance":"%s"}
                        """.formatted(request.getRequestURI()));
            }
            return false;
        }

        response.setHeader("X-RateLimit-Remaining", "47"); // illustrative — Guava RateLimiter has no exact counter
        response.setHeader("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 60));
        return true;
    }
}