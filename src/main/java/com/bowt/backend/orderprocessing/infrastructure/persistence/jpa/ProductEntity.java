package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * BIGSERIAL surrogate pk (internal, never leaves infrastructure) +
 * VARCHAR(50) business key `id` (e.g. "PROD-001", used everywhere else).
 */
@Entity
@Table(name = "products")
@Getter
@Setter
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", insertable = false, updatable = false)
    private Long pk;

    @NotNull
    @Column(name = "id", nullable = false, unique = true, updatable = false)
    private String id;

    @NotNull
    @Column(name = "sku", nullable = false, unique = true)
    private String sku;

    @NotNull
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @NotNull
    @Column(name = "inventory_quantity", nullable = false)
    private Integer inventoryQuantity;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ProductEntity() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.version == null) {
            this.version = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}