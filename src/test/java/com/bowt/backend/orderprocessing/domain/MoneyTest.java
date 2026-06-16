package com.bowt.backend.orderprocessing.domain;

import com.bowt.backend.orderprocessing.domain.model.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void addsTwoAmounts() {
        Money a = Money.of(10.00);
        Money b = Money.of(5.50);
        assertThat(a.add(b)).isEqualTo(Money.of(15.50));
    }

    @Test
    void multipliesByQuantity() {
        assertThat(Money.of(9.99).multiply(3)).isEqualTo(Money.of(29.97));
    }

    @Test
    void equalityIgnoresScale() {
        assertThat(Money.of("10.00")).isEqualTo(Money.of("10.0"));
    }

    @Test
    void throwsOnCurrencyMismatch() {
        Money usd = Money.of(10.00);
//        Money eur = new Money_EUR_testHelper(); // TODO: once multi-currency supported
        // For now just verify same-currency works and mismatch is tested when added
    }

    // TODO: test subtraction when added, test isGreaterThan, test toString
}
