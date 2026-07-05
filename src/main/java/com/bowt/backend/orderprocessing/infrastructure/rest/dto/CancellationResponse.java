package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import java.math.BigDecimal;

public record CancellationResponse(
        String orderId,
        String status,
        BigDecimal refundAmount,
        String refundTransactionId
) {
}