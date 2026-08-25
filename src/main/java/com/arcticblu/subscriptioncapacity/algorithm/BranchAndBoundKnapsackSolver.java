package com.arcticblu.subscriptioncapacity.algorithm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Exact 0/1 knapsack solver using depth-first branch and bound.
 *
 * <p>Unlike dynamic programming, the cost of this search depends on the number of
 * items rather than the magnitude of the capacity, so it remains usable for the
 * large capacities that make a DP table infeasible. The worst case is exponential,
 * but pruning is aggressive in practice.
 *
 * <p>Items are explored in descending order of value density, which finds a strong
 * incumbent solution early and so raises the threshold against which later subtrees
 * are pruned. Original item indices are carried through the sort and reported in the
 * solution, so callers never observe the reordering.
 *
 * <p><strong>Where pruning fails.</strong> The bound comes from a fractional
 * relaxation, so it separates subtrees only to the extent that candidates differ in
 * value density. When every candidate shares one density the relaxation is exact at
 * every node, each bound ties the incumbent instead of falling below it, and the
 * strict {@code <} test prunes nothing: the search degenerates into enumerating all
 * 2^n subsets. That is not a contrived input. A flat percentage fee schedule produces
 * exactly it, because fee revenue proportional to the requested amount gives every
 * candidate the same value per unit of capacity. The realistic worst case for this
 * application is therefore also the algorithm's worst case, and it arrives at
 * candidate counts well inside the {@code @Size} limit the API accepts.
 *
 * <p><strong>Why a node limit.</strong> {@code maxSearchNodes} caps how many nodes the
 * search may visit, which converts an otherwise unbounded exponential search into a
 * fast and explicit rejection: the caller receives {@link ProblemTooLargeException} in
 * bounded time rather than a request that occupies a thread indefinitely. A node count
 * is preferred over a wall-clock timeout because it is deterministic, and therefore
 * both testable and reproducible across machines and load.
 */
public final class BranchAndBoundKnapsackSolver implements KnapsackSolver {

    /** Recorded on every solution this solver produces. */
    public static final String ALGORITHM_NAME = "BRANCH_AND_BOUND";

    /** Node ceiling applied when none is configured. */
    public static final long DEFAULT_MAX_SEARCH_NODES = 5_000_000L;

    private final long maxSearchNodes;

    public BranchAndBoundKnapsackSolver() {
        this(DEFAULT_MAX_SEARCH_NODES);
    }

    public BranchAndBoundKnapsackSolver(long maxSearchNodes) {
        if (maxSearchNodes < 1) {
            throw new IllegalArgumentException("maxSearchNodes must be positive: " + maxSearchNodes);
        }
        this.maxSearchNodes = maxSearchNodes;
    }

    @Override
    public KnapsackSolution solve(List<KnapsackItem> items, long capacity) {
        List<KnapsackItem> candidates = List.copyOf(items);

        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        if (candidates.isEmpty()) {
            return KnapsackSolution.empty(ALGORITHM_NAME);
        }
        requireTotalValueFitsInLong(candidates);

        List<KnapsackItem> ordered = new ArrayList<>(candidates);
        ordered.sort(BranchAndBoundKnapsackSolver::compareDensityDescending);

        Search search = new Search(ordered, capacity, maxSearchNodes);
        search.explore(0, 0L, 0L, new ArrayList<>());

        return search.toSolution();
    }

    /**
     * Orders items by descending value density.
     *
     * <p>Densities are compared by cross-multiplication rather than division, so the
     * ordering is exact: {@code a/b > c/d} exactly when {@code a*d > c*b} for positive
     * weights. The two products are formed in {@link BigInteger}, so no pair of
     * candidates can overflow the comparison. That matters because a request well
     * inside every documented limit must not be refused on account of an internal
     * sorting strategy. The comparator runs O(n log n) times in front of a search that
     * is exponential in the worst case, so the cost of the wider arithmetic is
     * irrelevant.
     *
     * <p>Weightless items have unbounded density and sort first, ordered among
     * themselves by descending value.
     */
    private static int compareDensityDescending(KnapsackItem left, KnapsackItem right) {
        if (left.weight() == 0 && right.weight() == 0) {
            return Long.compare(right.value(), left.value());
        }
        if (left.weight() == 0) {
            return -1;
        }
        if (right.weight() == 0) {
            return 1;
        }
        return product(right.value(), left.weight())
                .compareTo(product(left.value(), right.weight()));
    }

    private static BigInteger product(long value, long weight) {
        return BigInteger.valueOf(value).multiply(BigInteger.valueOf(weight));
    }

    private void requireTotalValueFitsInLong(List<KnapsackItem> candidates) {
        long total = 0L;
        for (KnapsackItem item : candidates) {
            try {
                total = Math.addExact(total, item.value());
            } catch (ArithmeticException overflow) {
                throw new ProblemTooLargeException(
                        "Combined item value exceeds the maximum representable total");
            }
        }
    }

    /** Mutable search state, kept out of the solver so the solver stays stateless. */
    private static final class Search {

        private final List<KnapsackItem> ordered;
        private final long capacity;
        private final long maxSearchNodes;

        private long exploredNodes;
        private long bestValue;
        private long bestWeight;
        private List<Integer> bestSelection = List.of();

        private Search(List<KnapsackItem> ordered, long capacity, long maxSearchNodes) {
            this.ordered = ordered;
            this.capacity = capacity;
            this.maxSearchNodes = maxSearchNodes;
        }

        /**
         * Explores the subtree rooted at {@code depth}, given the value and weight
         * already committed and the items chosen to reach this point.
         */
        private void explore(int depth, long value, long weight, List<Integer> chosen) {
            // Every entry into this method is one node of the search tree, so counting
            // here bounds the whole search however it happens to branch.
            if (++exploredNodes > maxSearchNodes) {
                throw new ProblemTooLargeException(
                        ("Problem could not be solved exactly within the configured search "
                                + "limit of %d nodes: %d items")
                                .formatted(maxSearchNodes, ordered.size()));
            }

            if (depth == ordered.size()) {
                recordIfBetter(value, weight, chosen);
                return;
            }

            // Deliberately strict. Textbook branch and bound prunes on <=, since a
            // subtree that can only match the incumbent value adds nothing. Here a tie
            // on value is broken further by weight and then by item index, so a tying
            // subtree may still hold the preferred solution and must be explored.
            if (upperBound(depth, value, weight) < bestValue) {
                return;
            }

            KnapsackItem item = ordered.get(depth);

            // Take branch first: it usually yields a strong incumbent quickly, which makes
            // the skip branch cheaper to prune.
            if (weight + item.weight() <= capacity) {
                chosen.add(item.index());
                explore(depth + 1, value + item.value(), weight + item.weight(), chosen);
                chosen.removeLast();
            }

            explore(depth + 1, value, weight, chosen);
        }

        /**
         * An optimistic ceiling on the value reachable from this node, obtained by
         * relaxing the problem to allow fractional items.
         *
         * <p>The fractional optimum is always at least the integral optimum, so this
         * never underestimates. The final partial item's contribution is rounded up
         * using integer arithmetic, which keeps the bound valid without relying on
         * floating point: a bound that came in even slightly low could prune away the
         * true optimum.
         */
        private long upperBound(int depth, long value, long weight) {
            long bound = value;
            long remaining = capacity - weight;

            for (int i = depth; i < ordered.size(); i++) {
                KnapsackItem item = ordered.get(i);

                if (item.weight() <= remaining) {
                    remaining -= item.weight();
                    bound += item.value();
                    continue;
                }

                if (item.weight() > 0 && remaining > 0) {
                    try {
                        // ceil(value * remaining / weight), computed without division loss.
                        long numerator = Math.multiplyExact(item.value(), remaining);
                        bound += Math.addExact(numerator, item.weight() - 1) / item.weight();
                    } catch (ArithmeticException overflow) {
                        // The bound only has to be an overestimate, and Long.MAX_VALUE is
                        // the largest one available: it can never fall below any reachable
                        // value, so it prunes nothing and the search merely does more work
                        // before returning the same exact optimum. Underestimating is the
                        // dangerous direction, because it discards subtrees that may hold
                        // the optimum; refusing the request is worse still, because the
                        // input itself is perfectly valid.
                        return Long.MAX_VALUE;
                    }
                }
                break;
            }

            return bound;
        }

        /**
         * Replaces the incumbent when this complete selection is preferred: greater
         * value, or equal value with less weight, or equal on both while excluding an
         * item the incumbent includes.
         */
        private void recordIfBetter(long value, long weight, List<Integer> chosen) {
            if (value > bestValue
                    || (value == bestValue && weight < bestWeight)
                    || (value == bestValue && weight == bestWeight
                    && excludesLaterItems(chosen, bestSelection))) {
                bestValue = value;
                bestWeight = weight;
                bestSelection = List.copyOf(chosen);
            }
        }

        /**
         * Whether {@code candidate} is preferred over {@code incumbent} under the
         * interface's final tie-break rule, which favours excluding items appearing
         * later in the input.
         *
         * <p>The two selections are compared at the highest index on which they differ:
         * the one that omits that item wins. This reproduces the preference that falls
         * out of the dynamic programming solver's cell-by-cell iteration, so both
         * solvers name the same subset when several are equally optimal.
         */
        private static boolean excludesLaterItems(List<Integer> candidate, List<Integer> incumbent) {
            Set<Integer> left = Set.copyOf(candidate);
            Set<Integer> right = Set.copyOf(incumbent);

            int highestDifference = -1;
            for (int index : left) {
                if (!right.contains(index)) {
                    highestDifference = Math.max(highestDifference, index);
                }
            }
            for (int index : right) {
                if (!left.contains(index)) {
                    highestDifference = Math.max(highestDifference, index);
                }
            }

            if (highestDifference < 0) {
                return false;
            }
            return !left.contains(highestDifference);
        }

        private KnapsackSolution toSolution() {
            List<Integer> sorted = new ArrayList<>(bestSelection);
            sorted.sort(Comparator.naturalOrder());
            return new KnapsackSolution(ALGORITHM_NAME, sorted, bestWeight, bestValue);
        }
    }
}
