package com.bowt.backend.orderprocessing.infrastructure.rest.config;

import com.bowt.backend.orderprocessing.infrastructure.rest.ratelimit.DeprecationInterceptor;
import com.bowt.backend.orderprocessing.infrastructure.rest.ratelimit.RateLimitInterceptor;
import com.bowt.backend.orderprocessing.infrastructure.rest.security.ApiKeyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Interceptor execution order matters (NFR-6): ApiKey must run first (reject unauthenticated
 * traffic before spending a rate-limit token on it), then RateLimit (reject overload before
 * any domain work), then Deprecation (a cheap header add — no reason to run it early).
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final DeprecationInterceptor deprecationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(2);
        registry.addInterceptor(deprecationInterceptor)
                .addPathPatterns("/api/v1/**")
                .order(3);
    }
}