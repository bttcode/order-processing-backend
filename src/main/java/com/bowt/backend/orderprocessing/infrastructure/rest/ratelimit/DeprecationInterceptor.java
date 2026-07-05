package com.bowt.backend.orderprocessing.infrastructure.rest.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API-3: attaches the deprecation Warning header to every /api/v1/ response once v2 is active.
 */
@Component
public class DeprecationInterceptor implements HandlerInterceptor {

    private static final String WARNING = "299 - \"API version 1 deprecated, migrate to v2 by 2027-06-01\"";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getRequestURI().startsWith("/api/v1/")) {
            response.setHeader("Warning", WARNING);
        }
        return true;
    }
}