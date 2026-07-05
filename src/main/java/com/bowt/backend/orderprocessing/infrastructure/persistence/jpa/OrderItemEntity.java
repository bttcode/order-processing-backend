package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Dual product reference per ADR-001:
 * - product (FK -> products.pk, BIGINT)   : used for joins, fast, narrow index
 * - productId (VARCHAR(50) snapshot)      : NOT a FK — records the business key
 * at order-creation time, independent
 * of later renames/deletes of the product.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_pk", nullable = false)
    private OrderEntity order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_pk", nullable = false)
    private ProductEntity product;

    @Column(name = "product_id", nullable = false, updatable = false)
    private String productId; // snapshot business key, e.g. "PROD-001"

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    public OrderItemEntity() {
    }
}