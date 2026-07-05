package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.model.Order;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queries by the UUID public handle ({@code orders.id}), never the internal
 * BIGINT surrogate ({@code orders.pk}) — per ADR-001. The adapter implementing
 * this must route {@link #findById} through {@code idx_orders_public_id}, not
 * a primary-key lookup.
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(UUID publicId);

    List<Order> findByCustomerIdAndFilters(String customerId, OrderStatus status, Instant from,
                                           Instant to, Pageable pageable);

    long countByCustomerIdAndFilters(String customerId, OrderStatus status, Instant from, Instant to);

    /**
     * Minimal pagination carrier so the application layer does not depend on
     * {@code org.springframework.data.domain.Pageable} — keeps the port
     * Spring-Data-agnostic in case the JPA adapter is ever swapped (DEL-1
     * adapter-swap demo, LO-5).
     */
    record Pageable(int page, int size, String sortBy, boolean descending) {
        public static Pageable of(int page, int size) {
            return new Pageable(page, size, "createdAt", true);
        }
    }
}