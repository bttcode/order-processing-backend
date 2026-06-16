package com.bowt.backend.orderprocessing.domain.model;

import lombok.Data;

@Data
public class ShippingAddress {
    private String street;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    // Validation lives in the DTO layer (Bean Validation), not here
}
