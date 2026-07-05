package com.bowt.backend.orderprocessing.infrastructure.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotEmpty @Valid List<OrderItemRequest> items,
        @NotBlank String paymentMethod,
        @Valid ShippingAddressRequest shippingAddress
) {
}