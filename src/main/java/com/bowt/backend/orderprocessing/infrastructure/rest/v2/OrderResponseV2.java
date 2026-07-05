package com.bowt.backend.orderprocessing.infrastructure.rest.v2;

import com.bowt.backend.orderprocessing.infrastructure.rest.dto.OrderItemResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * [OI-14] v2 breaking change: `totalAmount` -> `total`. Same underlying Order, different DTO shape only.
 */
public record OrderResponseV2(
        String orderId,
        String customerId,
        String status,
        List<OrderItemResponse> items,
        BigDecimal total,
        String currency,
        String paymentMethod,
        String paymentTransactionId,
        Instant createdAt,
        Instant confirmedAt,
        LocalDate estimatedDeliveryDate
) {
}