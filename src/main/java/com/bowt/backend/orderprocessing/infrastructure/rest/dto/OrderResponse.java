package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrderResponse(
        String orderId,
        String customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String paymentMethod,
        String paymentTransactionId,
        Instant createdAt,
        Instant confirmedAt,
        LocalDate estimatedDeliveryDate,
        List<OrderItemResponse> items
) {
}
