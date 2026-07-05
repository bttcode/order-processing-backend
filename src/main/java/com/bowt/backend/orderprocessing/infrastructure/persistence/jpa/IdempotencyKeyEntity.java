package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * `key` is externally supplied by the client (Idempotency-Key header) so a plain
 * UUID PRIMARY KEY is correct here — unlike orders/products there is no surrogate pk
 * needed since this table is never joined against and inserts are naturally random
 * but low-volume relative to orders.
 * <p>
 * operationHash lets IdempotencyHandler distinguish "same key, same payload" (replay,
 * return cached response) from "same key, different payload" (409 conflict, per FR-8).
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "key", nullable = false)
    private UUID key;

    @NotNull
    @Column(name = "operation_hash", nullable = false)
    private String operationHash;

    @NotNull
    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @NotNull
    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyKeyEntity() {
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        if (this.expiresAt == null) {
            this.expiresAt = now.plusSeconds(24 * 3600); // FR-8: 24h TTL
        }
    }
}