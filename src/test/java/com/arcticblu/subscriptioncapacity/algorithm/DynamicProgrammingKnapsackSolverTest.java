package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DynamicProgrammingKnapsackSolverTest {

    private static final int RANDOM_TRIALS = 500;
    private static final long RANDOM_SEED = 20_250_821L;

    private final DynamicProgrammingKnapsackSolver solver = new DynamicProgrammingKnapsackSolver();

    // The assignment example: four investors, capacity 15. Investors A and B fill the
    // capacity exactly for 320, while the denser but smaller Investor C is a trap.
    private static final List<KnapsackItem> ASSIGNMENT_EXAMPLE = List.of(
            new KnapsackItem(0, 5, 120),   // Investor A
            new KnapsackItem(1, 10, 200),  // Investor B
            new KnapsackItem(2, 3, 80),    // Investor C
            new KnapsackItem(3, 8, 160));  // Investor D

    private static final long ASSIGNMENT_CAPACITY = 15;

    @Test
    @DisplayName("assignment example: picks items 0 and 1 for a total value of 320")
    void solvesAssignmentExample() {
        KnapsackSolution solution = solver.solve(ASSIGNMENT_EXAMPLE, ASSIGNMENT_CAPACITY);

        assertThat(solution.selectedIndices()).containsExactly(0, 1);
        assertThat(solution.totalValue()).isEqualTo(320);
        assertThat(solution.totalWeight()).isEqualTo(15);
    }

    @Test
    @DisplayName("assignment example: beats the 200 that value-density ordering settles for")
    void beatsGreedyOnAssignmentExample() {
        long greedyValue = greedyByDensity(ASSIGNMENT_EXAMPLE, ASSIGNMENT_CAPACITY);

        // Densities are C = 26.67, A = 24.0, D = 20.0, B = 20.0, so density takes C
        // then A -- 8 of 15 units consumed for 200 revenue -- after which neither D
        // nor B still fits.
        assertThat(greedyValue).isEqualTo(200);

        KnapsackSolution solution = solver.solve(ASSIGNMENT_EXAMPLE, ASSIGNMENT_CAPACITY);

        assertThat(solution.totalValue()).isEqualTo(320);
        assertThat(solution.totalValue()).isGreaterThan(greedyValue);
    }

    @Test
    @DisplayName("carry-forward counterexample: solving the pool at once beats optimising new candidates first")
    void solvesCarryForwardPoolBetterThanATwoStageDesign() {
        // A two-stage implementation (greedy on new candidates, then on old) would take X for 100
        // and have nothing left. This test proves we solve the whole pool at once to get 160,
        // and exists to prevent someone reintroducing a two-stage approach.
        List<KnapsackItem> pool = List.of(
                new KnapsackItem(0, 10, 100), // X, new
                new KnapsackItem(1, 5, 80),   // Y, carried forward
                new KnapsackItem(2, 5, 80));  // Z, carried forward

        KnapsackSolution solution = solver.solve(pool, 10);

        assertThat(solution.selectedIndices()).containsExactly(1, 2);
        assertThat(solution.totalValue()).isEqualTo(160);
        assertThat(solution.totalWeight()).isEqualTo(10);
    }

    @Test
    @DisplayName("the total value matches exhaustive search across randomised problems")
    void matchesBruteForceAcrossRandomisedProblems() {
        Random random = new Random(RANDOM_SEED);

        for (int trial = 1; trial <= RANDOM_TRIALS; trial++) {
            int itemCount = random.nextInt(12) + 1;
            long capacity = random.nextInt(40);

            List<KnapsackItem> items = new ArrayList<>(itemCount);
            for (int index = 0; index < itemCount; index++) {
                items.add(new KnapsackItem(index, random.nextInt(20), random.nextInt(100)));
            }

            KnapsackSolution solution = solver.solve(items, capacity);

            assertThat(solution.totalValue())
                    .as("value on trial %d, capacity %d, items %s", trial, capacity, items)
                    .isEqualTo(bruteForceBestValue(items, capacity));
            assertThat(solution.totalWeight())
                    .as("weight on trial %d, capacity %d, items %s", trial, capacity, items)
                    .isLessThanOrEqualTo(capacity);
        }
    }

    @Test
    @DisplayName("selected indices are echoed from the item rather than its list position")
    void echoesItemIndicesRatherThanListPositions() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(10, 5, 120),
                new KnapsackItem(20, 10, 200),
                new KnapsackItem(30, 3, 80),
                new KnapsackItem(40, 8, 160));

        KnapsackSolution solution = solver.solve(items, 15);

        assertThat(solution.selectedIndices()).containsExactly(10, 20);
        assertThat(solution.totalValue()).isEqualTo(320);
        assertThat(solution.totalWeight()).isEqualTo(15);
    }

    @Test
    @DisplayName("empty item list yields an empty solution")
    void returnsEmptySolutionForNoItems() {
        KnapsackSolution solution = solver.solve(List.of(), 100);

        assertEmptySolution(solution);
    }

    @Test
    @DisplayName("zero capacity yields an empty solution")
    void returnsEmptySolutionForZeroCapacity() {
        KnapsackSolution solution = solver.solve(List.of(new KnapsackItem(0, 1, 500)), 0);

        assertEmptySolution(solution);
    }

    @Test
    @DisplayName("zero capacity still admits weightless items")
    void selectsWeightlessItemsAtZeroCapacity() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 0, 40),
                new KnapsackItem(1, 1, 500));

        KnapsackSolution solution = solver.solve(items, 0);

        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(40);
        assertThat(solution.totalWeight()).isZero();
    }

    @Test
    @DisplayName("every item heavier than the capacity yields an empty solution")
    void returnsEmptySolutionWhenNothingFits() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 10, 100),
                new KnapsackItem(1, 20, 200));

        KnapsackSolution solution = solver.solve(items, 5);

        assertEmptySolution(solution);
    }

    @Test
    @DisplayName("a single item filling the capacity exactly is selected")
    void selectsSingleExactFit() {
        KnapsackSolution solution = solver.solve(List.of(new KnapsackItem(0, 42, 9_999)), 42);

        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(9_999);
        assertThat(solution.totalWeight()).isEqualTo(42);
    }

    @Test
    @DisplayName("a weightless item with value is selected even when nothing else fits")
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
    @DisplayName("everything is selected when the whole list fits")
    void selectsAllItemsWhenTheyAllFit() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 10, 5),
                new KnapsackItem(1, 20, 7),
                new KnapsackItem(2, 30, 9));

        KnapsackSolution solution = solver.solve(items, 100);

        assertThat(solution.selectedIndices()).containsExactly(0, 1, 2);
        assertThat(solution.totalValue()).isEqualTo(21);
        assertThat(solution.totalWeight()).isEqualTo(60);
    }

    @Test
    @DisplayName("equal-value subsets resolve to the lighter one")
    void breaksValueTiesTowardTheLighterSolution() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 10, 100),
                new KnapsackItem(1, 4, 100));

        KnapsackSolution solution = solver.solve(items, 10);

        assertThat(solution.selectedIndices()).containsExactly(1);
        assertThat(solution.totalValue()).isEqualTo(100);
        assertThat(solution.totalWeight()).isEqualTo(4);
    }

    @Test
    @DisplayName("equal-value, equal-weight subsets resolve to the earlier item")
    void breaksRemainingTiesTowardTheEarlierItem() {
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 5, 100),
                new KnapsackItem(1, 5, 100));

        KnapsackSolution solution = solver.solve(items, 5);

        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(100);
        assertThat(solution.totalWeight()).isEqualTo(5);
    }

    @Test
    @DisplayName("subsets tying on both value and weight resolve to the later pair")
    void breaksFullTiesBetweenDistinctSubsets() {
        // {0,3} and {1,2} both reach weight 4 for value 10, so "prefer the earlier
        // item" and "prefer to exclude the later item" pull in opposite directions.
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 1, 1),
                new KnapsackItem(1, 2, 5),
                new KnapsackItem(2, 2, 5),
                new KnapsackItem(3, 3, 9));

        KnapsackSolution solution = solver.solve(items, 4);

        assertThat(solution.selectedIndices()).containsExactly(1, 2);
        assertThat(solution.totalValue()).isEqualTo(10);
        assertThat(solution.totalWeight()).isEqualTo(4);
    }

    @Test
    @DisplayName("fractional fees scaled to minor units sum exactly")
    void sumsMinorUnitValuesExactly() {
        // 120.50 and 99.75 as cents; the 1.00 decoy is too heavy to join them.
        List<KnapsackItem> items = List.of(
                new KnapsackItem(0, 5, 12_050),
                new KnapsackItem(1, 5, 9_975),
                new KnapsackItem(2, 9, 100));

        KnapsackSolution solution = solver.solve(items, 10);

        assertThat(solution.selectedIndices()).containsExactly(0, 1);
        assertThat(solution.totalWeight()).isEqualTo(10);
        assertThat(solution.totalValue()).isEqualTo(22_025); // 220.25
    }

    @Test
    @DisplayName("a problem exceeding the table ceiling fails fast")
    void rejectsOversizedProblem() {
        KnapsackSolver boundedSolver = new DynamicProgrammingKnapsackSolver(100);
        List<KnapsackItem> items = List.of(new KnapsackItem(0, 1, 10));

        // 2 rows x 1001 columns = 2002 cells, well past the ceiling of 100.
        assertThatExceptionOfType(ProblemTooLargeException.class)
                .isThrownBy(() -> boundedSolver.solve(items, 1_000))
                .withMessageContaining("capacity 1000")
                .withMessageContaining("100 table cells");
    }

    @Test
    @DisplayName("a problem sitting exactly on the table ceiling still solves")
    void solvesProblemExactlyOnTableCeiling() {
        KnapsackSolver boundedSolver = new DynamicProgrammingKnapsackSolver(10);
        List<KnapsackItem> items = List.of(new KnapsackItem(0, 4, 50));

        // 2 rows x 5 columns = 10 cells, exactly the ceiling.
        KnapsackSolution solution = boundedSolver.solve(items, 4);

        assertThat(solution.selectedIndices()).containsExactly(0);
        assertThat(solution.totalValue()).isEqualTo(50);
        assertThat(solution.totalWeight()).isEqualTo(4);
    }

    @Test
    @DisplayName("the returned solution names the algorithm that produced it")
    void solutionNamesTheAlgorithm() {
        assertThat(solver.solve(ASSIGNMENT_EXAMPLE, ASSIGNMENT_CAPACITY).algorithmName())
                .isEqualTo("DYNAMIC_PROGRAMMING");
    }

    @Test
    @DisplayName("an empty solution names the algorithm too")
    void emptySolutionNamesTheAlgorithm() {
        assertThat(solver.solve(List.of(), 100).algorithmName()).isEqualTo("DYNAMIC_PROGRAMMING");
    }

    @Test
    @DisplayName("a non-positive table ceiling is rejected at construction")
    void rejectsNonPositiveTableCeiling() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DynamicProgrammingKnapsackSolver(0))
                .withMessageContaining("maxTableCells");
    }

    @Test
    @DisplayName("a null item list is rejected")
    void rejectsNullItemList() {
        assertThatNullPointerException()
                .isThrownBy(() -> solver.solve(null, 10));
    }

    @Test
    @DisplayName("a null item inside the list is rejected")
    void rejectsNullItemInsideList() {
        // Arrays.asList permits the null that List.of would reject outright,
        // so the solver is genuinely the thing under test here.
        List<KnapsackItem> items = Arrays.asList(new KnapsackItem(0, 1, 10), null);

        assertThatNullPointerException()
                .isThrownBy(() -> solver.solve(items, 10));
    }

    @Test
    @DisplayName("a capacity that would overflow the cell count fails fast")
    void rejectsCapacityNearLongMaxValue() {
        List<KnapsackItem> items = List.of(new KnapsackItem(0, 1, 10));

        assertThatExceptionOfType(ProblemTooLargeException.class)
                .isThrownBy(() -> solver.solve(items, Long.MAX_VALUE));
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
    @DisplayName("negative capacity is rejected")
    void rejectsNegativeCapacity() {
        List<KnapsackItem> items = List.of(new KnapsackItem(0, 1, 10));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> solver.solve(items, -1))
                .withMessageContaining("-1");
    }

    /**
     * Best value reachable by any subset, found by enumerating all 2^n bitmasks.
     * Returns the value alone: the optimal selection is not uniquely determined,
     * so only the optimum itself is safe to compare against.
     */
    private static long bruteForceBestValue(List<KnapsackItem> items, long capacity) {
        long best = 0L;

        for (int mask = 0; mask < (1 << items.size()); mask++) {
            long weight = 0L;
            long value = 0L;

            for (int position = 0; position < items.size(); position++) {
                if ((mask & (1 << position)) != 0) {
                    weight += items.get(position).weight();
                    value += items.get(position).value();
                }
            }

            if (weight <= capacity) {
                best = Math.max(best, value);
            }
        }

        return best;
    }

    private static void assertEmptySolution(KnapsackSolution solution) {
        assertThat(solution.selectedIndices()).isEmpty();
        assertThat(solution.totalWeight()).isZero();
        assertThat(solution.totalValue()).isZero();
    }

    /**
     * Total value produced by taking items in descending value-per-weight order --
     * the intuitive heuristic the exact solver has to beat. Densities are compared
     * by cross-multiplication to stay exact, which assumes positive weights and
     * modest values; both hold for every fixture it is used with.
     */
    private static long greedyByDensity(List<KnapsackItem> items, long capacity) {
        Comparator<KnapsackItem> byDescendingDensity =
                (left, right) -> Long.compare(right.value() * left.weight(), left.value() * right.weight());

        long remaining = capacity;
        long total = 0L;

        for (KnapsackItem candidate : items.stream().sorted(byDescendingDensity).toList()) {
            if (candidate.weight() <= remaining) {
                remaining -= candidate.weight();
                total += candidate.value();
            }
        }

        return total;
    }
}