package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

public record OrderItemResponse(
        String productId,
        String productName,
        int quantity,
        java.math.BigDecimal unitPrice,
        java.math.BigDecimal totalPrice
) {
}
