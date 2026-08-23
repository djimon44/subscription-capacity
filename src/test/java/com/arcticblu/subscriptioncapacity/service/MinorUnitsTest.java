package com.arcticblu.subscriptioncapacity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class MinorUnitsTest {

    @Test
    @DisplayName("a whole amount scales to hundredths")
    void scalesWholeAmount() {
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("15"), "amount")).isEqualTo(1500L);
    }

    @Test
    @DisplayName("an amount with two decimal places keeps both of them")
    void scalesTwoDecimalPlaces() {
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("15.75"), "amount")).isEqualTo(1575L);
    }

    @Test
    @DisplayName("a trailing zero in the second place is still a significant hundredth")
    void scalesTrailingZeroInSecondPlace() {
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("5.10"), "amount")).isEqualTo(510L);
    }

    @Test
    @DisplayName("trailing zeros beyond the second place are stripped before the precision check")
    void stripsTrailingZerosBeforeCheckingScale() {
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("5.100"), "amount")).isEqualTo(510L);
    }

    @Test
    @DisplayName("zero scales to zero")
    void scalesZero() {
        assertThat(MinorUnits.toMinorUnits(BigDecimal.ZERO, "amount")).isZero();
    }

    @Test
    @DisplayName("an amount whose stripped form carries a negative scale still scales correctly")
    void scalesValueWithNegativeScaleAfterStripping() {
        // stripTrailingZeros() turns 100 into 1E+2, a scale of -2; movePointRight accepts
        // that and this pins that the answer is still right.
        assertThat(new BigDecimal("100").stripTrailingZeros().scale()).isEqualTo(-2);
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("100"), "amount")).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("the smallest representable amount scales to a single minor unit")
    void scalesSmallestAmount() {
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("0.01"), "amount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an amount with three significant decimal places is rejected, naming the field and the value")
    void rejectsExcessPrecision() {
        assertThatExceptionOfType(InvalidSubscriptionInputException.class)
                .isThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("5.123"), "requestedAmount"))
                .withMessageContaining("requestedAmount")
                .withMessageContaining("5.123");
    }

    @Test
    @DisplayName("an amount too large to scale into a long is rejected as too large")
    void rejectsOverflowingAmount() {
        assertThatExceptionOfType(InvalidSubscriptionInputException.class)
                .isThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("99999999999999999"), "maxCapacity"))
                .withMessageContaining("maxCapacity")
                .withMessageContaining("too large");
    }

    @Test
    @DisplayName("minor units convert back to a decimal amount at the currency scale")
    void convertsBackToDecimal() {
        BigDecimal amount = MinorUnits.toDecimal(1575L);

        assertThat(amount).isEqualByComparingTo("15.75");
        assertThat(amount.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("zero minor units convert back to zero at the currency scale")
    void convertsZeroBackToDecimal() {
        BigDecimal amount = MinorUnits.toDecimal(0L);

        assertThat(amount).isEqualByComparingTo("0.00");
        assertThat(amount.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a round trip through minor units preserves the amount")
    void roundTripPreservesValue() {
        assertThat(MinorUnits.toDecimal(MinorUnits.toMinorUnits(new BigDecimal("15.75"), "amount")))
                .isEqualByComparingTo("15.75");
        assertThat(MinorUnits.toDecimal(MinorUnits.toMinorUnits(new BigDecimal("0.01"), "amount")))
                .isEqualByComparingTo("0.01");
        assertThat(MinorUnits.toDecimal(MinorUnits.toMinorUnits(new BigDecimal("100"), "amount")))
                .isEqualByComparingTo("100");
    }
}
