package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.List;

/**
 * The selected subset of a knapsack problem.
 *
 * <p>The solution records which algorithm produced it. A solver is not necessarily
 * tied to a single algorithm — {@link AdaptiveKnapsackSolver} chooses per request —
 * so the name belongs to the result rather than to the solver that returned it.
 *
 * @param algorithmName   identifier of the algorithm that produced this result, persisted
 *                        with the run; at most 32 characters, matching the column it is
 *                        stored in
 * @param selectedIndices indices of chosen items, in ascending order
 * @param totalWeight     combined weight of the selection, in minor units
 * @param totalValue      combined value of the selection, in minor units
 */
public record KnapsackSolution(String algorithmName,
                               List<Integer> selectedIndices,
                               long totalWeight,
                               long totalValue) {

    private static final int MAX_ALGORITHM_NAME_LENGTH = 32;

    public KnapsackSolution {
        if (algorithmName == null) {
            throw new IllegalArgumentException("algorithmName must not be null");
        }
        if (algorithmName.isBlank()) {
            throw new IllegalArgumentException("algorithmName must not be blank");
        }
        if (algorithmName.length() > MAX_ALGORITHM_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "algorithmName must be at most %d characters: %s"
                            .formatted(MAX_ALGORITHM_NAME_LENGTH, algorithmName));
        }
        selectedIndices = List.copyOf(selectedIndices);
        if (totalWeight < 0) {
            throw new IllegalArgumentException("totalWeight must not be negative: " + totalWeight);
        }
        if (totalValue < 0) {
            throw new IllegalArgumentException("totalValue must not be negative: " + totalValue);
        }
    }

    public static KnapsackSolution empty(String algorithmName) {
        return new KnapsackSolution(algorithmName, List.of(), 0L, 0L);
    }
}
