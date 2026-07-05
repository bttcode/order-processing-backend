package com.bowt.backend.orderprocessing.domain.model;

import com.bowt.backend.orderprocessing.domain.model.enumeration.Currency;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Nested
    class Construction {

        @Test
        void ofBigDecimal_normalizesToScale2() {
            Money m = Money.of(new BigDecimal("10"));
            assertThat(m.getAmount()).isEqualByComparingTo("10.00");
        }

        @Test
        void ofDouble_avoidsFloatingPointArtifacts() {
            // BigDecimal.valueOf(double) goes through Double.toString(),
            // so 0.1 + 0.2 must land on exactly 0.30, not 0.30000000000000004.
            Money m = Money.of(0.1).add(Money.of(0.2));
            assertThat(m).isEqualTo(Money.of("0.30"));
        }

        @Test
        void ofString_roundsHalfUpWhenTruncating() {
            Money m = Money.of("19.999");
            assertThat(m.getAmount()).isEqualByComparingTo("20.00");
        }

        @Test
        void roundsHalfUpOnExactMidpoint() {
            Money m = Money.of("10.005");
            assertThat(m.getAmount()).isEqualByComparingTo("10.01");
        }

        @Test
        void defaultsToUsdCurrency() {
            assertThat(Money.of(5).getCurrency()).isEqualTo(Currency.USD);
        }

        @Test
        void zeroConstantIsZero() {
            assertThat(Money.ZERO.getAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    class Add {

        @Test
        void addsTwoAmounts() {
            assertThat(Money.of("10.50").add(Money.of("5.25")))
                    .isEqualTo(Money.of("15.75"));
        }

        @Test
        void doesNotMutateEitherOperand() {
            Money a = Money.of("10.00");
            Money b = Money.of("5.00");

            a.add(b);

            assertThat(a).isEqualTo(Money.of("10.00"));
            assertThat(b).isEqualTo(Money.of("5.00"));
        }

        @Test
        void addingZeroIsIdentity() {
            Money a = Money.of("42.42");
            assertThat(a.add(Money.ZERO)).isEqualTo(a);
        }
    }

    @Nested
    class Multiply {

        @Test
        void multipliesByPositiveFactor() {
            assertThat(Money.of("9.99").multiply(3)).isEqualTo(Money.of("29.97"));
        }

        @Test
        void multiplyByZeroYieldsZero() {
            assertThat(Money.of("9.99").multiply(0)).isEqualTo(Money.ZERO);
        }

        @Test
        void multiplyByNegativeFactorYieldsNegativeAmount() {
            assertThat(Money.of("9.99").multiply(-1)).isEqualTo(Money.of("-9.99"));
        }
    }

    @Nested
    class Comparison {

        @Test
        void isGreaterThan_trueWhenLarger() {
            assertThat(Money.of("10.01").isGreaterThan(Money.of("10.00"))).isTrue();
        }

        @Test
        void isGreaterThan_falseWhenEqual() {
            assertThat(Money.of("10.00").isGreaterThan(Money.of("10.00"))).isFalse();
        }

        @Test
        void isGreaterThan_falseWhenSmaller() {
            assertThat(Money.of("9.99").isGreaterThan(Money.of("10.00"))).isFalse();
        }
    }

    @Nested
    class EqualsAndHashCode {

        @Test
        void equalAmountsWithDifferentInputScaleAreEqual() {
            // "10" and "10.00" both normalize to scale 2 at construction time.
            assertThat(Money.of("10")).isEqualTo(Money.of("10.00"));
        }

        @Test
        void equalObjectsHaveSameHashCode() {
            assertThat(Money.of("10.00").hashCode()).isEqualTo(Money.of("10").hashCode());
        }

        @Test
        void notEqualToDifferentAmount() {
            assertThat(Money.of("10.00")).isNotEqualTo(Money.of("10.01"));
        }

        @Test
        void notEqualToNullOrUnrelatedType() {
            assertThat(Money.of("10.00")).isNotEqualTo(null);
            assertThat(Money.of("10.00")).isNotEqualTo("10.00");
        }

        @Test
        void referenceEqualityShortCircuitsInEquals() {
            Money m = Money.of("10.00");
            assertThat(m.equals(m)).isTrue();
        }
    }

    @Nested
    class ToStringFormat {

        @Test
        void formatsAsPlainAmountAndCurrencyCode() {
            assertThat(Money.of("10.5").toString()).isEqualTo("10.50 USD");
        }

        @Test
        void doesNotUseScientificNotationForSmallOrLargeValues() {
            assertThat(Money.of("0.01").toString()).isEqualTo("0.01 USD");
            assertThat(Money.of("1000000").toString()).isEqualTo("1000000.00 USD");
        }
    }
}