package com.bowt.backend.orderprocessing.infrastructure.rest.v1;

import com.bowt.backend.orderprocessing.infrastructure.rest.dto.OrderItemResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * [OI-14] v1 shape: `totalAmount`, no page metadata on list responses.
 */
public record OrderResponseV1(
        String orderId,
        String customerId,
        String status,
        List<OrderItemResponse> items,
        BigDecimal totalAmount,
        String currency,
        String paymentMethod,
        String paymentTransactionId,
        Instant createdAt,
        Instant confirmedAt,
        LocalDate estimatedDeliveryDate,
        Map<String, Map<String, String>> _links
) {
}