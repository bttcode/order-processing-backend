package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Shipping address for the order")
public record ShippingAddressRequest(
        @NotBlank String street,
        @NotBlank String city,
        @NotBlank String state,
        @NotBlank String postalCode,
        @NotBlank String country
) {
}