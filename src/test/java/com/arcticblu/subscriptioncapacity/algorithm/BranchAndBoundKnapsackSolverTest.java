package com.arcticblu.subscriptioncapacity.algorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class BranchAndBoundKnapsackSolverTest {

    private final KnapsackSolver solver = new BranchAndBoundKnapsackSolver();
    private final KnapsackSolver referenceSolver = new DynamicProgrammingKnapsackSolver();

    private static final List<KnapsackItem> ASSIGNMENT_EXAMPLE = List.of(
            new KnapsackItem(0, 5, 120),
            new KnapsackItem(1, 10, 200),
            new KnapsackItem(2, 3, 80),
            new KnapsackItem(3, 8, 160));

    @Test
    @DisplayName("assignment example: picks investors A and B for a total value of 320")
    void solvesAssignmentExample() {
        KnapsackSolution solution = solver.solve(ASSIGNMENT_EXAMPLE, 15);

        assertThat(solution.selectedIndices()).containsExactly(0, 1);
        assertThat(solution.totalValue()).isEqualTo(320);
        assertThat(solution.totalWeight()).isEqualTo(15);
    }

    @Test
    @DisplayName("reports its own algorithm name")
    void reportsAlgorithmName() {
        assertThat(solver.name()).isEqualTo("BRANCH_AND_BOUND");
        assertThat(solver.name().length()).isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("solves a capacity far beyond any feasible dynamic programming table")
    void solvesVeryLargeCapacity() {
        // 50,000,000.00 in minor units. A DP table for this would need roughly
        // 5 x 10^9 columns; this search does not depend on the capacity value at all.
        long capacity = 5_000_000_000L;

        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 2_000_000_000L, 30_000L),
                new KnapsackItem(1, 3_000_000_000L, 50_000L),
                new KnapsackItem(2, 2_500_000_000L, 45_000L),
                new KnapsackItem(3, 1_000_000_000L, 15_000L));

        KnapsackSolution solution = solver.solve(items, capacity);

        // Items 1 and 2 exceed capacity together (5.5e9), so the best pair within
        // 5e9 is items 0 and 1 at 80,000.
        assertThat(solution.totalValue()).isEqualTo(80_000L);
        assertThat(solution.totalWeight()).isLessThanOrEqualTo(capacity);
    }

    @Test
    @DisplayName("empty item list yields an empty solution")
    void returnsEmptySolutionForNoItems() {
        assertEmptySolution(solver.solve(List.of(), 100));
    }

    @Test
    @DisplayName("zero capacity yields an empty solution")
    void returnsEmptySolutionForZeroCapacity() {
        assertEmptySolution(solver.solve(List.of(new KnapsackItem(0, 1, 500)), 0));
    }

    @Test
    @DisplayName("every item heavier than the capacity yields an empty solution")
    void returnsEmptySolutionWhenNothingFits() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 10, 100),
                new KnapsackItem(1, 20, 200));

        assertEmptySolution(solver.solve(items, 5));
    }

    @Test
    @DisplayName("a weightless item with value is always selected")
    void alwaysSelectsWeightlessItemWithValue() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 0, 25),
                new KnapsackItem(1, 7, 50));

        KnapsackSolution solution = solver.solve(items, 5);

        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(25);
        assertThat(solution.totalWeight()).isZero();
    }

    @Test
    @DisplayName("indices are echoed from the item, not from the density-sorted position")
    void returnsOriginalItemIndices() {
        // Densities are 26.67, 24.0, 20.0, 20.0, so the search reorders these entirely.
        List<KnapsackItem> items = List.of(
                new KnapsackItem(10, 5, 120),
                new KnapsackItem(20, 10, 200),
                new KnapsackItem(30, 3, 80),
                new KnapsackItem(40, 8, 160));

        KnapsackSolution solution = solver.solve(items, 15);

        assertThat(solution.selectedIndices()).containsExactly(10, 20);
    }

    @Test
    @DisplayName("negative capacity is rejected")
    void rejectsNegativeCapacity() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> solver.solve(List.of(new KnapsackItem(0, 1, 10)), -1))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("a combined value overflowing a long fails fast")
    void rejectsTotalValueOverflow() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 1, Long.MAX_VALUE),
                new KnapsackItem(1, 1, 1));

        assertThatExceptionOfType(ProblemTooLargeException.class)
                .isThrownBy(() -> solver.solve(items, 10))
                .withMessageContaining("Combined item value");
    }

    @Test
    @DisplayName("agrees with dynamic programming on randomised problems")
    void agreesWithDynamicProgramming() {
        Random random = new Random(20260824L);

        for (int trial = 0; trial < 1_000; trial++) {
            int count = 1 + random.nextInt(14);
            List<KnapsackItem> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                items.add(new KnapsackItem(i, random.nextInt(25), random.nextInt(100)));
            }
            long capacity = random.nextInt(60);

            KnapsackSolution actual = solver.solve(items, capacity);
            KnapsackSolution expected = referenceSolver.solve(items, capacity);

            assertThat(actual.totalValue())
                    .as("trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(expected.totalValue());

            assertThat(actual.totalWeight())
                    .as("trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(expected.totalWeight());

            assertThat(actual.totalWeight()).isLessThanOrEqualTo(capacity);
            assertThat(sumWeights(items, actual)).isEqualTo(actual.totalWeight());
            assertThat(sumValues(items, actual)).isEqualTo(actual.totalValue());
        }
    }

    @Test
    @DisplayName("selects the same subset as dynamic programming on randomised problems")
    void selectsSameSubsetAsDynamicProgramming() {
        Random random = new Random(20260824L);

        for (int trial = 0; trial < 1_000; trial++) {
            int count = 1 + random.nextInt(14);
            List<KnapsackItem> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                items.add(new KnapsackItem(i, random.nextInt(25), random.nextInt(100)));
            }
            long capacity = random.nextInt(60);

            assertThat(solver.solve(items, capacity).selectedIndices())
                    .as("trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(referenceSolver.solve(items, capacity).selectedIndices());
        }
    }

    private static long sumWeights(List<KnapsackItem> items, KnapsackSolution solution) {
        return items.stream()
                .filter(item -> solution.selectedIndices().contains(item.index()))
                .mapToLong(KnapsackItem::weight)
                .sum();
    }

    private static long sumValues(List<KnapsackItem> items, KnapsackSolution solution) {
        return items.stream()
                .filter(item -> solution.selectedIndices().contains(item.index()))
                .mapToLong(KnapsackItem::value)
                .sum();
    }

    private static void assertEmptySolution(KnapsackSolution solution) {
        assertThat(solution.selectedIndices()).isEmpty();
        assertThat(solution.totalWeight()).isZero();
        assertThat(solution.totalValue()).isZero();
    }
}