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

    /** Size and capacity of the flat-fee fixture the node-limit tests share. */
    private static final int FLAT_FEE_COUNT = 18;
    private static final long FLAT_FEE_CAPACITY = 8_550L;

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
    @DisplayName("the returned solution names the algorithm that produced it")
    void solutionNamesTheAlgorithm() {
        assertThat(solver.solve(ASSIGNMENT_EXAMPLE, 15).algorithmName())
                .isEqualTo("BRANCH_AND_BOUND");
    }

    @Test
    @DisplayName("an empty solution names the algorithm too")
    void emptySolutionNamesTheAlgorithm() {
        assertThat(solver.solve(List.of(), 100).algorithmName()).isEqualTo("BRANCH_AND_BOUND");
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

    // --- density arithmetic --------------------------------------------------------

    @Test
    @DisplayName("candidates whose density product overflows a long are solved, not refused")
    void solvesCandidatesWhoseDensityProductOverflowsALong() {
        // 250,000,000.00 requested for a 5,000,000.00 fee, in minor units. Ordering by
        // density cross-multiplies value by weight, which here is 1.25 x 10^19 and so
        // past Long.MAX_VALUE, yet the request is well inside every documented limit.
        long amount = 25_000_000_000L;
        long fee = 500_000_000L;
        long capacity = 30_000_000_000L;

        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, amount, fee),
                new KnapsackItem(1, amount, fee));

        KnapsackSolution solution = solver.solve(items, capacity);

        // The identical problem divided through by a million, which is small enough for
        // a dynamic programming table and so can say what the answer must be.
        long scale = 1_000_000L;
        KnapsackSolution expected = referenceSolver.solve(
                List.of(
                        new KnapsackItem(0, amount / scale, fee / scale),
                        new KnapsackItem(1, amount / scale, fee / scale)),
                capacity / scale);

        assertThat(solution.selectedIndices()).isEqualTo(expected.selectedIndices());
        assertThat(solution.totalValue()).isEqualTo(expected.totalValue() * scale);
        assertThat(solution.totalWeight()).isEqualTo(expected.totalWeight() * scale);
    }

    @Test
    @DisplayName("an upper bound whose arithmetic overflows loosens the bound rather than failing")
    void solvesWhenTheUpperBoundArithmeticOverflows() {
        // The partial item's contribution is value x remaining, which is 10^28 here.
        // Overflowing that must yield an unusably large bound, not a rejection.
        long capacity = 3_000_000_000_000_000_000L;

        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 4_000_000_000_000_000_000L, 5_000_000_000L),
                new KnapsackItem(1, 1_000_000_000_000_000_000L, 2_000_000_000L));

        KnapsackSolution solution = solver.solve(items, capacity);

        assertThat(solution.selectedIndices()).containsExactly(1);
        assertThat(solution.totalValue()).isEqualTo(2_000_000_000L);
        assertThat(solution.totalWeight()).isEqualTo(1_000_000_000_000_000_000L);
    }

    // --- the node limit ------------------------------------------------------------

    @Test
    @DisplayName("a small problem is solved well inside the default node limit")
    void smallProblemIsUnaffectedByTheNodeLimit() {
        List<KnapsackItem> items = flatFeeCandidates(FLAT_FEE_COUNT);

        KnapsackSolution solution = solver.solve(items, FLAT_FEE_CAPACITY);

        assertThat(solution.totalValue())
                .isEqualTo(referenceSolver.solve(items, FLAT_FEE_CAPACITY).totalValue());
        assertThat(solution.totalWeight()).isLessThanOrEqualTo(FLAT_FEE_CAPACITY);
    }

    @Test
    @DisplayName("a problem the default limit solves is refused under a low one")
    void refusesProblemThatExceedsTheConfiguredNodeLimit() {
        List<KnapsackItem> items = flatFeeCandidates(FLAT_FEE_COUNT);

        assertThat(solver.solve(items, FLAT_FEE_CAPACITY).totalValue()).isPositive();

        assertThatExceptionOfType(ProblemTooLargeException.class)
                .isThrownBy(() -> new BranchAndBoundKnapsackSolver(100L)
                        .solve(items, FLAT_FEE_CAPACITY));
    }

    @Test
    @DisplayName("the refusal names the configured limit and the item count")
    void refusalNamesTheLimitAndTheItemCount() {
        List<KnapsackItem> items = flatFeeCandidates(FLAT_FEE_COUNT);

        assertThatExceptionOfType(ProblemTooLargeException.class)
                .isThrownBy(() -> new BranchAndBoundKnapsackSolver(100L)
                        .solve(items, FLAT_FEE_CAPACITY))
                .withMessageContaining("could not be solved exactly")
                .withMessageContaining("100")
                .withMessageContaining("%d items".formatted(FLAT_FEE_COUNT));
    }

    @Test
    @DisplayName("a non-positive node limit is rejected")
    void rejectsNonPositiveNodeLimit() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new BranchAndBoundKnapsackSolver(0L))
                .withMessageContaining("0");
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

    /**
     * Candidates that all share one value density, which is what a flat percentage fee
     * schedule produces and the shape against which the fractional bound prunes nothing.
     * At this size the full enumeration is a few hundred thousand nodes: far past the
     * hundred-node limit the refusal tests configure, and far short of the default.
     */
    private static List<KnapsackItem> flatFeeCandidates(int count) {
        List<KnapsackItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long amount = 100L * (i + 1);
            items.add(new KnapsackItem(i, amount, 2 * amount));
        }
        return items;
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