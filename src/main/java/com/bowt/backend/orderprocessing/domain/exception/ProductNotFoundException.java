package com.bowt.backend.orderprocessing.domain.exception;

import lombok.Getter;

@Getter
public class ProductNotFoundException extends RuntimeException {

    private final String productId;

    public ProductNotFoundException(String productId) {
        super("Product not found: " + productId);
        this.productId = productId;
    }
}