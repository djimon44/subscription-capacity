package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.List;

/**
 * The selected subset of a knapsack problem.
 *
 * @param selectedIndices indices of chosen items, in ascending order
 * @param totalWeight     combined weight of the selection, in minor units
 * @param totalValue      combined value of the selection, in minor units
 */
public record KnapsackSolution(List<Integer> selectedIndices, long totalWeight, long totalValue) {

    public KnapsackSolution {
        selectedIndices = List.copyOf(selectedIndices);
        if (totalWeight < 0) {
            throw new IllegalArgumentException("totalWeight must not be negative: " + totalWeight);
        }
        if (totalValue < 0) {
            throw new IllegalArgumentException("totalValue must not be negative: " + totalValue);
        }
    }

    public static KnapsackSolution empty() {
        return new KnapsackSolution(List.of(), 0L, 0L);
    }
}