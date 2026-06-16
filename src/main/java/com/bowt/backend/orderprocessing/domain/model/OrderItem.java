package com.bowt.backend.orderprocessing.domain.model;

import lombok.Getter;

import java.util.Objects;

@Getter
public class OrderItem {

    private final String productId;
    private final String productName;
    private final int quantity;
    private final Money unitPrice;
    private final Money totalPrice;

    public OrderItem(String productId, String productName, int quantity, Money unitPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got: " + quantity);
        }
        this.productId   = Objects.requireNonNull(productId);
        this.productName = Objects.requireNonNull(productName);
        this.quantity    = quantity;
        this.unitPrice   = Objects.requireNonNull(unitPrice);
        this.totalPrice  = unitPrice.multiply(quantity);
    }
}
