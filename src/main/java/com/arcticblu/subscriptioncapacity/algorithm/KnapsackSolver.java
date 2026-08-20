package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.List;

public interface KnapsackSolver {

    /**
     * Selects the subset of items with the greatest total value whose combined
     * weight does not exceed {@code capacity}.
     *
     * <p>Weights are expressed in indivisible integer units; callers are
     * responsible for scaling decimal amounts before invoking the solver.
     *
     * <p>Where several subsets achieve the same total value, the solution using
     * the least capacity is returned; remaining ties are broken toward items
     * appearing earlier in {@code knapsackItems}.
     *
     * @param knapsackItems    candidate items, never null
     * @param capacity maximum total weight, never negative
     * @return the optimal selection, never null; empty when nothing fits
     * @throws CapacityTooLargeException if the problem exceeds solver limits
     */
    KnapsackSolution solve(List<KnapsackItem> knapsackItems, long capacity);
}
