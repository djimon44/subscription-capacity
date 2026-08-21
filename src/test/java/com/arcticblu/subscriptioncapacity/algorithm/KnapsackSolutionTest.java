package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class KnapsackSolutionTest {

    @Test
    @DisplayName("the index list is copied, so later changes to the caller's list are not visible")
    void copiesIndexListDefensively() {
        List<Integer> mutableIndices = new ArrayList<>(List.of(0, 1));

        KnapsackSolution solution = new KnapsackSolution(mutableIndices, 15, 320);
        mutableIndices.add(2);
        mutableIndices.clear();

        assertThat(solution.selectedIndices()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("the returned index list cannot be modified")
    void returnsUnmodifiableIndexList() {
        KnapsackSolution solution = new KnapsackSolution(List.of(0, 1), 15, 320);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> solution.selectedIndices().add(2));
    }

    @Test
    @DisplayName("a negative total weight is rejected")
    void rejectsNegativeTotalWeight() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution(List.of(), -1, 0))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("a negative total value is rejected")
    void rejectsNegativeTotalValue() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution(List.of(), 0, -1))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("the empty solution selects nothing and totals zero")
    void emptySolutionSelectsNothing() {
        KnapsackSolution solution = KnapsackSolution.empty();

        assertThat(solution.selectedIndices()).isEmpty();
        assertThat(solution.totalWeight()).isZero();
        assertThat(solution.totalValue()).isZero();
    }
}
