package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaOrderRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {

    /**
     * Read path (GET /orders/{id}) — always needs items, so eager-fetch here.
     */
    @Query("SELECT o FROM OrderEntity o LEFT JOIN FETCH o.items WHERE o.id = :publicId")
    Optional<OrderEntity> findByPublicId(@Param("publicId") UUID publicId);

    /**
     * [I-6 perf fix] Update path (OrderJpaAdapter.save() on an existing order) does NOT
     * need items eagerly loaded — the adapter replaces them via orphanRemoval regardless.
     * The JOIN FETCH variant above was previously reused here, loading items that were
     * immediately discarded. This variant skips that unnecessary round trip.
     */
    Optional<OrderEntity> findEntityByPublicId(UUID publicId);

    Optional<OrderEntity> findByIdempotencyKey(UUID idempotencyKey);
}