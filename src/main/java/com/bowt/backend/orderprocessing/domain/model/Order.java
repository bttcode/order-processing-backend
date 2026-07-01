package com.bowt.backend.orderprocessing.domain.model;

import com.bowt.backend.orderprocessing.domain.exception.InvalidOrderStateException;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import com.bowt.backend.orderprocessing.domain.model.enumeration.PaymentMethod;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Aggregate root for the order domain.
 * Zero Spring annotations — pure Java.
 * State transitions are enforced via transitionTo() — never set status directly from outside.
 * ID must be injected by the application layer (factory or use-case) — not self-generated here.
 */
@Getter
public class Order {

    private UUID id;
    private String customerId;
    private OrderStatus status;
    private final List<OrderItem> items;
    private PaymentMethod paymentMethod;
    private String paymentTransactionId; // TODO: why need this?
    private ShippingAddress shippingAddress;
    private LocalDate estimatedDeliveryDate;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;

    /**
     * Package-private no-arg constructor. Used only by the static reconstruct() factory.
     * Do not use in business logic.
     */
    Order() {
        this.items = new ArrayList<>();
    }

    /**
     * Primary constructor for new orders.
     * ID must be supplied by the caller (generated in the application layer).
     */
    public Order(UUID id, String customerId) {
        this();

        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.status = OrderStatus.PENDING_VALIDATION;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Static factory for reconstructing an Order from persisted state.
     * Bypasses transitionTo() intentionally — the status was already validated
     * when it was first written. Used exclusively by OrderMapper.toDomain().
     */
    public static Order reconstruct(UUID id,
                                    String customerId,
                                    OrderStatus status,
                                    PaymentMethod paymentMethod,
                                    String paymentTransactionId,
                                    ShippingAddress shippingAddress,
                                    LocalDate estimatedDeliveryDate,
                                    Instant createdAt,
                                    Instant updatedAt,
                                    Instant confirmedAt) {
        Order o = new Order();
        o.id = id;
        o.customerId = customerId;
        o.status = status;
        o.paymentMethod = paymentMethod;
        o.paymentTransactionId = paymentTransactionId;
        o.shippingAddress = shippingAddress;
        o.estimatedDeliveryDate = estimatedDeliveryDate;
        o.createdAt = createdAt;
        o.updatedAt = updatedAt;
        o.confirmedAt = confirmedAt;
        return o;
    }

    // ── Explicit setters for non-state fields only ─────────────────────────

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
        this.updatedAt = Instant.now();
    }

    public void setPaymentTransactionId(String paymentTransactionId) {
        this.paymentTransactionId = paymentTransactionId;
        this.updatedAt = Instant.now();
    }

    public void setShippingAddress(ShippingAddress shippingAddress) {
        this.shippingAddress = shippingAddress;
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

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    // ── State Machine ──────────────────────────────────────────────────────

    /**
     * Only valid forward transitions are allowed.
     * Callers must use this method — never set status directly from outside.
     */
    public void transitionTo(OrderStatus nextStatus) {
        if (!OrderStatus.canTransition(this.status, nextStatus)) {
            throw new InvalidOrderStateException(this.id, this.status, nextStatus);
        }
        this.status = nextStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Convenience: confirm order after payment authorized.
     * Sets confirmedAt and calculates delivery date.
     */
    public void confirm(String transactionId) {
        transitionTo(OrderStatus.CONFIRMED);
        this.confirmedAt = Instant.now();
        this.paymentTransactionId = Objects.requireNonNull(transactionId);
        this.estimatedDeliveryDate = addBusinessDays(LocalDate.now(), 3);
    }

    private LocalDate addBusinessDays(LocalDate start, int days) {
        LocalDate result = start;
        int added = 0;
        while (added < days) {
            result = result.plusDays(1);
            // Skip Saturday (6) and Sunday (7)
            if (result.getDayOfWeek().getValue() < 6) {
                added++;
            }
        }
        return result;
    }
}