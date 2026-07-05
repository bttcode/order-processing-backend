package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaIdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    Optional<IdempotencyKeyEntity> findByKey(UUID key);

    /**
     * [OI-6] Backing query for the scheduled cleanup job — uses idx_idempotency_expires.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKeyEntity k WHERE k.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}