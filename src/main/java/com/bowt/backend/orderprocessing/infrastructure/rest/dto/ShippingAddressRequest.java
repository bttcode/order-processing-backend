package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record ShippingAddressRequest(
        @NotBlank String street,
        @NotBlank String city,
        @NotBlank String state,
        @NotBlank String postalCode,
        @NotBlank String country
) {}
