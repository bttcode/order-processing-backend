package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaProductRepository extends JpaRepository<ProductEntity, Long> {

    @Query("SELECT p FROM ProductEntity p WHERE p.id = :businessId")
    Optional<ProductEntity> findByBusinessId(@Param("businessId") String businessId);

    /**
     * [I-2 fix] OrderJpaAdapter previously called findByBusinessId once PER order item —
     * an N+1 query. At 10k orders/sec * ~2 items avg = 20k extra SELECTs/sec, enough to
     * blow the p99 target on its own. This batches all lookups for one order into a
     * single `WHERE id IN (...)` query.
     */
    @Query("SELECT p FROM ProductEntity p WHERE p.id IN :ids")
    List<ProductEntity> findAllByBusinessIds(@Param("ids") List<String> ids);
}