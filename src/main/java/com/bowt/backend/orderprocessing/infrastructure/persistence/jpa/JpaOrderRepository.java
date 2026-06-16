package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JpaOrderRepository extends JpaRepository<OrderEntity, Long> {
    @Query("SELECT o FROM OrderEntity o LEFT JOIN FETCH o.items WHERE o.id = :publicId")
    Optional<OrderEntity> findByPublicId(@Param("publicId") UUID publicId);
}