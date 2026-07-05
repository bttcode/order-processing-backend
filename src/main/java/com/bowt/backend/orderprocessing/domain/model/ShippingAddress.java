package com.bowt.backend.orderprocessing.domain.model;

public record ShippingAddress(String street, String city, String state,
                              String postalCode, String country) {
}