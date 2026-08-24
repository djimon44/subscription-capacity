package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class KnapsackSolutionTest {

    private static final String ALGORITHM = "DYNAMIC_PROGRAMMING";

    @Test
    @DisplayName("the index list is copied, so later changes to the caller's list are not visible")
    void copiesIndexListDefensively() {
        List<Integer> mutableIndices = new ArrayList<>(List.of(0, 1));

        KnapsackSolution solution = new KnapsackSolution(ALGORITHM, mutableIndices, 15, 320);
        mutableIndices.add(2);
        mutableIndices.clear();

        assertThat(solution.selectedIndices()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("the returned index list cannot be modified")
    void returnsUnmodifiableIndexList() {
        KnapsackSolution solution = new KnapsackSolution(ALGORITHM, List.of(0, 1), 15, 320);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> solution.selectedIndices().add(2));
    }

    @Test
    @DisplayName("a negative total weight is rejected")
    void rejectsNegativeTotalWeight() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution(ALGORITHM, List.of(), -1, 0))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("a negative total value is rejected")
    void rejectsNegativeTotalValue() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution(ALGORITHM, List.of(), 0, -1))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("the empty solution selects nothing, totals zero, and keeps the algorithm name")
    void emptySolutionSelectsNothing() {
        KnapsackSolution solution = KnapsackSolution.empty(ALGORITHM);

        assertThat(solution.algorithmName()).isEqualTo(ALGORITHM);
        assertThat(solution.selectedIndices()).isEmpty();
        assertThat(solution.totalWeight()).isZero();
        assertThat(solution.totalValue()).isZero();
    }

    @Test
    @DisplayName("a null algorithm name is rejected")
    void rejectsNullAlgorithmName() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution(null, List.of(), 0, 0))
                .withMessageContaining("algorithmName");
    }

    @Test
    @DisplayName("a blank algorithm name is rejected")
    void rejectsBlankAlgorithmName() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution("   ", List.of(), 0, 0))
                .withMessageContaining("algorithmName");
    }

    @Test
    @DisplayName("an algorithm name longer than the column it is stored in is rejected")
    void rejectsOverlongAlgorithmName() {
        String tooLong = "A".repeat(33);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new KnapsackSolution(tooLong, List.of(), 0, 0))
                .withMessageContaining("32");
    }

    @Test
    @DisplayName("an algorithm name of exactly the column width is accepted")
    void acceptsAlgorithmNameAtColumnWidth() {
        String atLimit = "A".repeat(32);

        assertThat(new KnapsackSolution(atLimit, List.of(), 0, 0).algorithmName())
                .isEqualTo(atLimit);
    }
}
