package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.model.Product;

import java.util.List;
import java.util.Optional;

/**
 * Queries by the VARCHAR(50) business key ({@code products.id}, e.g.
 * "PROD-001") per ADR-001 — the adapter resolves the BIGINT {@code pk}
 * internally for the {@code order_items} FK; the domain never sees it.
 */
public interface ProductRepository {

    Optional<Product> findById(String businessId);

    /**
     * Batch lookup — added specifically to fix infrastructure-review.md I-2:
     * {@code OrderJpaAdapter.resolveProductEntities} was issuing one SELECT
     * per order item (~20k extra queries/sec at target load). The adapter
     * must implement this as a single {@code WHERE id IN (...)} query.
     */
    List<Product> findAllById(List<String> businessIds);

    Product save(Product product);
}