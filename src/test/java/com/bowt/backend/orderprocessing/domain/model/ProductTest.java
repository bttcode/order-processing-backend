package com.bowt.backend.orderprocessing.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-3 (zero overselling) is enforced at the domain level here, before any
 * database-level optimistic locking ever gets involved. These tests are the
 * cheapest possible gate for that invariant — no Spring, no Testcontainers.
 */
class ProductTest {

    private Product product(int stock) {
        return new Product("PROD-1", "SKU-1", "Widget", Money.of("9.99"), stock);
    }

    // ── isAvailable ──────────────────────────────────────────────────────

    @Test
    void isAvailable_trueWhenStockCoversRequest() {
        assertThat(product(10).isAvailable(10)).isTrue();
    }

    @Test
    void isAvailable_falseWhenStockInsufficient() {
        assertThat(product(5).isAvailable(6)).isFalse();
    }

    @Test
    void isAvailable_trueAtExactBoundary() {
        assertThat(product(1).isAvailable(1)).isTrue();
    }

    @Test
    void isAvailable_falseWhenStockIsZero() {
        assertThat(product(0).isAvailable(1)).isFalse();
    }

    // ── deductStock ──────────────────────────────────────────────────────

    @Test
    void deductStock_succeedsAndDecrementsWhenSufficient() {
        Product p = product(10);

        boolean result = p.deductStock(4);

        assertThat(result).isTrue();
        assertThat(p.getInventoryQuantity()).isEqualTo(6);
    }

    @Test
    void deductStock_succeedsAtExactStockBoundary() {
        Product p = product(5);

        boolean result = p.deductStock(5);

        assertThat(result).isTrue();
        assertThat(p.getInventoryQuantity()).isZero();
    }

    @Test
    void deductStock_failsWhenInsufficient_andLeavesInventoryUnchanged() {
        Product p = product(3);

        boolean result = p.deductStock(4);

        assertThat(result).isFalse();
        assertThat(p.getInventoryQuantity()).isEqualTo(3); // never oversold
    }

    @Test
    void deductStock_failsForZeroRequest() {
        Product p = product(10);

        assertThat(p.deductStock(0)).isFalse();
        assertThat(p.getInventoryQuantity()).isEqualTo(10);
    }

    @Test
    void deductStock_failsForNegativeRequest() {
        Product p = product(10);

        assertThat(p.deductStock(-1)).isFalse();
        assertThat(p.getInventoryQuantity()).isEqualTo(10);
    }

    @Test
    void deductStock_neverDrivesInventoryNegative() {
        Product p = product(2);

        // Repeated failed attempts to over-deduct must never touch the count.
        p.deductStock(5);
        p.deductStock(3);

        assertThat(p.getInventoryQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(p.getInventoryQuantity()).isEqualTo(2);
    }

    // ── restoreStock ─────────────────────────────────────────────────────

    @Test
    void restoreStock_succeedsAndIncrementsForPositiveRequest() {
        Product p = product(5);

        boolean result = p.restoreStock(3);

        assertThat(result).isTrue();
        assertThat(p.getInventoryQuantity()).isEqualTo(8);
    }

    @Test
    void restoreStock_failsForZeroRequest() {
        Product p = product(5);

        assertThat(p.restoreStock(0)).isFalse();
        assertThat(p.getInventoryQuantity()).isEqualTo(5);
    }

    @Test
    void restoreStock_failsForNegativeRequest() {
        Product p = product(5);

        assertThat(p.restoreStock(-2)).isFalse();
        assertThat(p.getInventoryQuantity()).isEqualTo(5);
    }

    // ── round trips ──────────────────────────────────────────────────────

    @Test
    void deductThenRestore_roundTripsToOriginalQuantity() {
        Product p = product(20);

        p.deductStock(7);
        p.restoreStock(7);

        assertThat(p.getInventoryQuantity()).isEqualTo(20);
    }

    @Test
    void immutableFieldsExposedCorrectly() {
        Product p = new Product("PROD-9", "SKU-9", "Gadget", Money.of("19.99"), 42);

        assertThat(p.getId()).isEqualTo("PROD-9");
        assertThat(p.getSku()).isEqualTo("SKU-9");
        assertThat(p.getName()).isEqualTo("Gadget");
        assertThat(p.getPrice()).isEqualTo(Money.of("19.99"));
    }
}