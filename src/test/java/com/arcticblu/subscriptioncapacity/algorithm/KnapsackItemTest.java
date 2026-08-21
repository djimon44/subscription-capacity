package com.arcticblu.subscriptioncapacity.algorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class KnapsackItemTest {

    @Test
    @DisplayName("a negative index is rejected")
    void rejectsNegativeIndex() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackItem(-1, 10, 100))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("a negative weight is rejected")
    void rejectsNegativeWeight() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackItem(0, -5, 100))
                .withMessageContaining("-5");
    }

    @Test
    @DisplayName("a negative value is rejected")
    void rejectsNegativeValue() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackItem(0, 10, -7))
                .withMessageContaining("-7");
    }

    @Test
    @DisplayName("zero index, weight and value are all accepted")
    void acceptsZeroes() {
        KnapsackItem item = new KnapsackItem(0, 0, 0);

        assertThat(item.index()).isZero();
        assertThat(item.weight()).isZero();
        assertThat(item.value()).isZero();
    }

    @Test
    @DisplayName("a valid item exposes the values it was constructed with")
    void exposesConstructorArguments() {
        KnapsackItem item = new KnapsackItem(7, 250, 12_050);

        assertThat(item.index()).isEqualTo(7);
        assertThat(item.weight()).isEqualTo(250);
        assertThat(item.value()).isEqualTo(12_050);
    }
}
