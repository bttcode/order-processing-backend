package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record OrderItemRequest(
        @NotBlank String productId,
        @Positive int quantity
) {
}