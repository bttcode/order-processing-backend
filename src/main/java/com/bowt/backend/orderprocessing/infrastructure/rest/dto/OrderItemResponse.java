package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        String productId,
        String productName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
) {
}