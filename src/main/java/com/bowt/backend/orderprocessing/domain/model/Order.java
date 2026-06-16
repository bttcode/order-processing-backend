package com.bowt.backend.orderprocessing.domain.model;

import com.bowt.backend.orderprocessing.domain.exception.InvalidOrderStateException;
import com.bowt.backend.orderprocessing.domain.util.OrderIdGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Aggregate root for the order domain.
 * Zero Spring annotations — pure Java.
 * State transitions are enforced via transitionTo() — never set status directly from outside.
 */
@Getter
@Setter
public class Order {

    private final List<OrderItem> items = new ArrayList<>();
    private UUID id;
    private String customerId;
    private OrderStatus status;
    private String paymentMethod;
    private String paymentTransactionId;
    private ShippingAddress shippingAddress;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private LocalDate estimatedDeliveryDate;
    private int version;

    // Required by mapper — do not use directly in business logic
    public Order() {
    }

    public Order(String customerId) {
        this.id = OrderIdGenerator.generate();
        this.customerId = Objects.requireNonNull(customerId);
        this.status = OrderStatus.PENDING_VALIDATION;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // ── Items ──────────────────────────────────────────────────────────────

    public void addItem(OrderItem item) {
        Objects.requireNonNull(item);
        this.items.add(item);
        this.updatedAt = Instant.now();
    }

    public Money getTotalAmount() {
        return items.stream()
                .map(OrderItem::getTotalPrice)
                .reduce(Money.ZERO, Money::add);
    }

    // ── State Machine ──────────────────────────────────────────────────────

    /**
     * Only valid forward transitions are allowed.
     * Callers must use this method — never set status via setStatus() from outside.
     */
    public void transitionTo(OrderStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new InvalidOrderStateException(this.id, this.status, next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /**
     * Convenience: confirm order after payment authorized.
     * Sets confirmedAt and calculates delivery date.
     */
    public void confirm(String transactionId) {
        transitionTo(OrderStatus.CONFIRMED);
        this.paymentTransactionId = Objects.requireNonNull(transactionId);
        this.confirmedAt = Instant.now();
        // +3 business days — no public holiday handling per spec
        this.estimatedDeliveryDate = addBusinessDays(LocalDate.now(), 3);
    }

    private LocalDate addBusinessDays(LocalDate start, int days) {
        LocalDate result = start;
        int added = 0;
        while (added < days) {
            result = result.plusDays(1);
            // Skip Saturday (6) and Sunday (7)
            if (result.getDayOfWeek().getValue() < 6) added++;
        }
        return result;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
