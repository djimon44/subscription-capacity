package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.List;

/**
 * Exact 0/1 knapsack solver that picks an algorithm per request.
 *
 * <p>Dynamic programming is preferred: it is predictable and fast whenever its table
 * fits the configured ceiling. When the capacity is too large for that table, the
 * request falls through to branch and bound, whose cost depends on the number of items
 * rather than the magnitude of the capacity. A caller therefore never sees
 * {@link ProblemTooLargeException} merely because the capacity was large.
 *
 * <p>Which algorithm ran is reported by the returned solution's
 * {@link KnapsackSolution#algorithmName()}, since it varies with the input.
 */
public final class AdaptiveKnapsackSolver implements KnapsackSolver {

    private final int maxTableCells;
    private final DynamicProgrammingKnapsackSolver dynamicProgramming;
    private final BranchAndBoundKnapsackSolver branchAndBound;

    public AdaptiveKnapsackSolver() {
        this(DynamicProgrammingKnapsackSolver.DEFAULT_MAX_TABLE_CELLS);
    }

    public AdaptiveKnapsackSolver(int maxTableCells) {
        if (maxTableCells < 1) {
            throw new IllegalArgumentException("maxTableCells must be positive: " + maxTableCells);
        }
        this.maxTableCells = maxTableCells;
        this.dynamicProgramming = new DynamicProgrammingKnapsackSolver(maxTableCells);
        this.branchAndBound = new BranchAndBoundKnapsackSolver();
    }

    @Override
    public KnapsackSolution solve(List<KnapsackItem> items, long capacity) {
        return fitsDynamicProgrammingTable(items, capacity)
                ? dynamicProgramming.solve(items, capacity)
                : branchAndBound.solve(items, capacity);
    }

    /**
     * Whether the dynamic programming table for this problem stays within the ceiling.
     *
     * <p>Mirrors the guard inside {@link DynamicProgrammingKnapsackSolver} exactly, so a
     * problem routed to dynamic programming never fails there: the test is by division,
     * never multiplication, so no product can overflow.
     */
    private boolean fitsDynamicProgrammingTable(List<KnapsackItem> items, long capacity) {
        int rows = items.size() + 1;
        return capacity <= (long) maxTableCells / rows - 1;
    }
}
