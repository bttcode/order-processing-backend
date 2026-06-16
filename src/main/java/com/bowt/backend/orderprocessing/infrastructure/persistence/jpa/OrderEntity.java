package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import com.bowt.backend.orderprocessing.domain.model.OrderStatus;
import com.bowt.backend.orderprocessing.domain.util.OrderIdGenerator;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", insertable = false, updatable = false)
    private Long pk;

    @Column(name = "id", nullable = false, unique = true, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false)
    private String currency = "USD";

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "payment_transaction_id")
    private String paymentTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "estimated_delivery_date")
    private LocalDate estimatedDeliveryDate;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItemEntity> items = new ArrayList<>();

    @PrePersist
    void ensurePublicId() {
        if (this.id == null) {
            this.id = OrderIdGenerator.generate();
        }
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.updatedAt == null) this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
