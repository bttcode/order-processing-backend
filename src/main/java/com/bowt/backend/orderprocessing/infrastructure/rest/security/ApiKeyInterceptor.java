package com.bowt.backend.orderprocessing.infrastructure.rest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * NFR-6: simplified auth by design. Keys are statically configured (application.yml),
 * no rotation, no key store. Runs first in the interceptor chain (see WebMvcConfiguration)
 * so an invalid key is rejected before rate-limit accounting or deprecation-header work.
 * <p>
 * [OI-3] This interceptor only validates the key exists in the static allow-list — it does
 * NOT feed key validity into RateLimitInterceptor. If per-key rate limiting is later
 * introduced (OI-15), keying on an *unvalidated* header value would let an attacker
 * exhaust a legitimate customer's rate-limit bucket by guessing/spoofing their key. That
 * refactor must validate before keying, and is out of scope for this learning project.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final Set<String> validKeys;

    public ApiKeyInterceptor(@Value("${security.api-keys:demo-key-001}") String configuredKeys) {
        this.validKeys = new HashSet<>(Arrays.asList(configuredKeys.split(",")));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (request.getRequestURI().startsWith("/actuator/health")) {
            return true; // health endpoints are never versioned or key-protected
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || !validKeys.contains(apiKey)) {
            writeProblem(response, request.getRequestURI());
            return false;
        }
        return true;
    }

    private void writeProblem(HttpServletResponse response, String instance) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        try (PrintWriter writer = response.getWriter()) {
            writer.write("""
                    {"type":"https://api.example.com/errors/unauthorized",
                     "title":"Unauthorized",
                     "status":401,
                     "detail":"Missing or invalid X-API-Key header",
                     "instance":"%s"}
                    """.formatted(instance));
        }
    }
}