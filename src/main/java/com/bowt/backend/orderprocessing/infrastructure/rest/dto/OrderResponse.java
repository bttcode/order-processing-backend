package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

public record OrderResponse(
        String orderId,
        String customerId,
        String status,
        java.math.BigDecimal totalAmount,
        String currency,
        String paymentMethod,
        String paymentTransactionId,
        java.time.Instant createdAt,
        java.time.Instant confirmedAt,
        java.time.LocalDate estimatedDeliveryDate,
        java.util.List<OrderItemResponse> items
) {
}
