package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface JpaProductRepository extends JpaRepository<ProductEntity, Long> {
    @Query("SELECT p FROM ProductEntity p WHERE p.id = :businessId")
    Optional<ProductEntity> findByBusinessId(@Param("businessId") String businessId);
}
