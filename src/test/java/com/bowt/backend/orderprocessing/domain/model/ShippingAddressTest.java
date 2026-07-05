package com.bowt.backend.orderprocessing.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShippingAddress is a record — equals/hashCode/toString are compiler-generated.
 * These tests exist mainly to pin the field order and catch an accidental
 * change from record to class (which would silently drop value semantics).
 */
class ShippingAddressTest {

    private ShippingAddress address() {
        return new ShippingAddress("123 Main St", "San Francisco", "CA", "94102", "US");
    }

    @Test
    void exposesAllComponentsViaAccessors() {
        ShippingAddress addr = address();

        assertThat(addr.street()).isEqualTo("123 Main St");
        assertThat(addr.city()).isEqualTo("San Francisco");
        assertThat(addr.state()).isEqualTo("CA");
        assertThat(addr.postalCode()).isEqualTo("94102");
        assertThat(addr.country()).isEqualTo("US");
    }

    @Test
    void equalityIsValueBasedNotReferenceBased() {
        ShippingAddress a = address();
        ShippingAddress b = new ShippingAddress("123 Main St", "San Francisco", "CA", "94102", "US");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotSameAs(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differsWhenAnySingleComponentDiffers() {
        ShippingAddress base = address();

        assertThat(base).isNotEqualTo(new ShippingAddress("456 Other Ave", "San Francisco", "CA", "94102", "US"));
        assertThat(base).isNotEqualTo(new ShippingAddress("123 Main St", "Oakland", "CA", "94102", "US"));
        assertThat(base).isNotEqualTo(new ShippingAddress("123 Main St", "San Francisco", "NY", "94102", "US"));
        assertThat(base).isNotEqualTo(new ShippingAddress("123 Main St", "San Francisco", "CA", "10001", "US"));
        assertThat(base).isNotEqualTo(new ShippingAddress("123 Main St", "San Francisco", "CA", "94102", "CA"));
    }

    @Test
    void toStringContainsAllFieldValues() {
        assertThat(address().toString())
                .contains("123 Main St", "San Francisco", "CA", "94102", "US");
    }
}