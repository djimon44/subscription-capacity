package com.arcticblu.subscriptioncapacity.algorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AdaptiveKnapsackSolverTest {

    private static final int RANDOM_TRIALS = 500;
    private static final long RANDOM_SEED = 20_260_824L;

    private final KnapsackSolver solver = new AdaptiveKnapsackSolver();

    // The assignment example: four investors, capacity 15. A and B fill it exactly for 320.
    private static final List<KnapsackItem> ASSIGNMENT_EXAMPLE = List.of(
            new KnapsackItem(0, 5, 120),
            new KnapsackItem(1, 10, 200),
            new KnapsackItem(2, 3, 80),
            new KnapsackItem(3, 8, 160));

    @Test
    @DisplayName("a small problem is solved by dynamic programming and says so")
    void solvesSmallProblemWithDynamicProgramming() {
        KnapsackSolution solution = solver.solve(ASSIGNMENT_EXAMPLE, 15);

        assertThat(solution.algorithmName()).isEqualTo("DYNAMIC_PROGRAMMING");
        assertThat(solution.selectedIndices()).containsExactly(0, 1);
        assertThat(solution.totalValue()).isEqualTo(320);
        assertThat(solution.totalWeight()).isEqualTo(15);
    }

    @Test
    @DisplayName("a capacity past the table ceiling falls through to branch and bound")
    void fallsThroughToBranchAndBoundPastTheCeiling() {
        // 2 rows, so the ceiling is 10 / 2 - 1 = 4. A capacity of 5 would need 12 cells.
        KnapsackSolver boundedSolver = new AdaptiveKnapsackSolver(10);
        List<KnapsackItem> items = List.of(new KnapsackItem(0, 4, 50));

        KnapsackSolution solution = boundedSolver.solve(items, 5);

        assertThat(solution.algorithmName()).isEqualTo("BRANCH_AND_BOUND");
        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(50);
        assertThat(solution.totalWeight()).isEqualTo(4);
    }

    @Test
    @DisplayName("a problem sitting exactly on the table ceiling still uses dynamic programming")
    void staysOnDynamicProgrammingAtTheCeiling() {
        // 2 rows x 5 columns = 10 cells, exactly the ceiling the bounded solver allows.
        KnapsackSolver boundedSolver = new AdaptiveKnapsackSolver(10);
        List<KnapsackItem> items = List.of(new KnapsackItem(0, 4, 50));

        KnapsackSolution solution = boundedSolver.solve(items, 4);

        assertThat(solution.algorithmName()).isEqualTo("DYNAMIC_PROGRAMMING");
        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(50);
        assertThat(solution.totalWeight()).isEqualTo(4);
    }

    @Test
    @DisplayName("the row count moves the ceiling, so more items push the same capacity across it")
    void ceilingTightensAsItemsAreAdded() {
        // Ceiling with 1 item is 30 / 2 - 1 = 14; with 5 items it is 30 / 6 - 1 = 4.
        KnapsackSolver boundedSolver = new AdaptiveKnapsackSolver(30);

        List<KnapsackItem> oneItem = List.of(new KnapsackItem(0, 3, 10));
        List<KnapsackItem> fiveItems = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            fiveItems.add(new KnapsackItem(index, 3, 10));
        }

        assertThat(boundedSolver.solve(oneItem, 14).algorithmName()).isEqualTo("DYNAMIC_PROGRAMMING");
        assertThat(boundedSolver.solve(fiveItems, 14).algorithmName()).isEqualTo("BRANCH_AND_BOUND");
    }

    @Test
    @DisplayName("a capacity far beyond any feasible table is solved rather than rejected")
    void solvesCapacityBeyondAnyFeasibleTable() {
        // 50,000,000.00 in minor units. A DP table for this would need roughly
        // 5 x 10^9 columns, so the request would previously have been refused outright.
        long capacity = 5_000_000_000L;

        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 2_000_000_000L, 30_000L),
                new KnapsackItem(1, 3_000_000_000L, 50_000L),
                new KnapsackItem(2, 2_500_000_000L, 45_000L),
                new KnapsackItem(3, 1_000_000_000L, 15_000L));

        assertThatNoException().isThrownBy(() -> solver.solve(items, capacity));

        KnapsackSolution solution = solver.solve(items, capacity);

        // Items 1 and 2 exceed the capacity together (5.5e9), so the best pair within
        // 5e9 is items 0 and 1 at 80,000.
        assertThat(solution.algorithmName()).isEqualTo("BRANCH_AND_BOUND");
        assertThat(solution.selectedIndices()).containsExactly(0, 1);
        assertThat(solution.totalValue()).isEqualTo(80_000L);
        assertThat(solution.totalWeight()).isEqualTo(capacity);
    }

    @Test
    @DisplayName("a capacity at the very top of the long range is solved rather than overflowing")
    void solvesCapacityAtTheTopOfTheLongRange() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 10, 100),
                new KnapsackItem(1, 20, 200));

        KnapsackSolution solution = solver.solve(items, Long.MAX_VALUE);

        assertThat(solution.algorithmName()).isEqualTo("BRANCH_AND_BOUND");
        assertThat(solution.selectedIndices()).containsExactly(0, 1);
        assertThat(solution.totalValue()).isEqualTo(300);
        assertThat(solution.totalWeight()).isEqualTo(30);
    }

    @Test
    @DisplayName("matches dynamic programming exactly across randomised problems, whichever branch it takes")
    void matchesDynamicProgrammingAcrossRandomisedProblems() {
        // The ceiling is deliberately tight so the routing varies from trial to trial;
        // the reference solver is unbounded and always runs dynamic programming.
        KnapsackSolver adaptiveSolver = new AdaptiveKnapsackSolver(300);
        KnapsackSolver referenceSolver = new DynamicProgrammingKnapsackSolver();

        Random random = new Random(RANDOM_SEED);
        int dynamicProgrammingTrials = 0;
        int branchAndBoundTrials = 0;

        for (int trial = 1; trial <= RANDOM_TRIALS; trial++) {
            int itemCount = 1 + random.nextInt(14);
            long capacity = random.nextInt(60);

            List<KnapsackItem> items = new ArrayList<>(itemCount);
            for (int index = 0; index < itemCount; index++) {
                items.add(new KnapsackItem(index, random.nextInt(25), random.nextInt(100)));
            }

            KnapsackSolution actual = adaptiveSolver.solve(items, capacity);
            KnapsackSolution expected = referenceSolver.solve(items, capacity);

            assertThat(actual.selectedIndices())
                    .as("indices on trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(expected.selectedIndices());
            assertThat(actual.totalValue())
                    .as("value on trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(expected.totalValue());
            assertThat(actual.totalWeight())
                    .as("weight on trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(expected.totalWeight());

            if (actual.algorithmName().equals("DYNAMIC_PROGRAMMING")) {
                dynamicProgrammingTrials++;
            } else {
                branchAndBoundTrials++;
            }
        }

        // Without this the cross-check could silently degrade into comparing one branch
        // against itself while the other went entirely unexercised.
        assertThat(dynamicProgrammingTrials).isPositive();
        assertThat(branchAndBoundTrials).isPositive();
    }

    @Test
    @DisplayName("empty item list yields an empty solution that still names an algorithm")
    void returnsEmptySolutionForNoItems() {
        KnapsackSolution solution = solver.solve(List.of(), 100);

        assertThat(solution.selectedIndices()).isEmpty();
        assertThat(solution.totalWeight()).isZero();
        assertThat(solution.totalValue()).isZero();
        assertThat(solution.algorithmName()).isNotBlank();
    }

    @Test
    @DisplayName("negative capacity is rejected by whichever solver runs")
    void rejectsNegativeCapacity() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> solver.solve(List.of(new KnapsackItem(0, 1, 10)), -1))
                .withMessageContaining("-1");
    }

    @Test
    @DisplayName("a non-positive table ceiling is rejected at construction")
    void rejectsNonPositiveTableCeiling() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AdaptiveKnapsackSolver(0))
                .withMessageContaining("maxTableCells");
    }
}
