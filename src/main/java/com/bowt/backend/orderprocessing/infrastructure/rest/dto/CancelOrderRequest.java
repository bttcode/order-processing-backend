package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelOrderRequest(@NotBlank String reason) {
}