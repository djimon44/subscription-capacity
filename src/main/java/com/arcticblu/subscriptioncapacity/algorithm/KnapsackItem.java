package com.arcticblu.subscriptioncapacity.algorithm;

/**
 * A single knapsack candidate.
 *
 * @param index  position in the caller's original collection, echoed back in the solution
 * @param weight capacity consumed, in indivisible integer minor units
 * @param value  benefit gained if selected, in indivisible integer minor units
 */
public record KnapsackItem(int index, long weight, long value) {

    public KnapsackItem {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight must not be negative: " + weight);
        }
        if (value < 0) {
            throw new IllegalArgumentException("value must not be negative: " + value);
        }
    }
}
