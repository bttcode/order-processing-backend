package com.bowt.backend.orderprocessing.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemTest {

    @Test
    void computesTotalPriceAsUnitPriceTimesQuantity() {
        OrderItem item = new OrderItem("PROD-1", "Widget", 3, Money.of("9.99"));

        assertThat(item.getTotalPrice()).isEqualTo(Money.of("29.97"));
    }

    @Test
    void quantityOfOneYieldsTotalEqualToUnitPrice() {
        OrderItem item = new OrderItem("PROD-1", "Widget", 1, Money.of("5.00"));

        assertThat(item.getTotalPrice()).isEqualTo(Money.of("5.00"));
    }

    @Test
    void rejectsZeroQuantity() {
        assertThatThrownBy(() -> new OrderItem("PROD-1", "Widget", 0, Money.of("9.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be positive");
    }

    @Test
    void rejectsNegativeQuantity() {
        assertThatThrownBy(() -> new OrderItem("PROD-1", "Widget", -5, Money.of("9.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-5");
    }

    @Test
    void rejectsNullProductId() {
        assertThatThrownBy(() -> new OrderItem(null, "Widget", 1, Money.of("9.99")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullProductName() {
        assertThatThrownBy(() -> new OrderItem("PROD-1", null, 1, Money.of("9.99")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullUnitPrice() {
        assertThatThrownBy(() -> new OrderItem("PROD-1", "Widget", 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void preservesConstructorArgumentsVerbatim() {
        Money price = Money.of("49.99");
        OrderItem item = new OrderItem("PROD-42", "Wireless Mouse", 2, price);

        assertThat(item.getProductId()).isEqualTo("PROD-42");
        assertThat(item.getProductName()).isEqualTo("Wireless Mouse");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getUnitPrice()).isEqualTo(price);
    }

    @Test
    void totalPriceIsComputedOnceAtConstructionTime() {
        // OrderItem is immutable: totalPrice must reflect the constructor's
        // quantity/unitPrice forever, with no recompute path exposed.
        OrderItem item = new OrderItem("PROD-1", "Widget", 4, Money.of("2.50"));

        assertThat(item.getTotalPrice()).isEqualTo(Money.of("10.00"));
        // calling getters repeatedly must not change the result
        assertThat(item.getTotalPrice()).isEqualTo(item.getTotalPrice());
    }
}