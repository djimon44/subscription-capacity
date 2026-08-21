package com.arcticblu.subscriptioncapacity.algorithm;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact 0/1 knapsack solver using dynamic programming.
 *
 * <p>All quantities are expressed in indivisible integer minor units; callers
 * scale decimal amounts before invoking the solver.
 *
 * <p>Runs in O(n · capacity) time and space. Because the cost scales with the
 * capacity <em>value</em> rather than its digit count, the algorithm is
 * pseudo-polynomial: fast for modest capacities, infeasible for large ones.
 * {@code maxTableCells} bounds the table so oversized problems fail fast with a
 * clear message rather than exhausting the heap.
 *
 * <p>Two tables of {@code long} (16 bytes per cell) plus one {@code boolean}
 * table (1 byte per cell) are allocated, so the default ceiling corresponds to
 * roughly 170 MB.
 */
public final class DynamicProgrammingKnapsackSolver implements KnapsackSolver {

    public static final int DEFAULT_MAX_TABLE_CELLS = 10_000_000;

    private final int maxTableCells;

    public DynamicProgrammingKnapsackSolver() {
        this(DEFAULT_MAX_TABLE_CELLS);
    }

    public DynamicProgrammingKnapsackSolver(int maxTableCells) {
        if (maxTableCells < 1) {
            throw new IllegalArgumentException("maxTableCells must be positive: " + maxTableCells);
        }
        this.maxTableCells = maxTableCells;
    }

    @Override
    public KnapsackSolution solve(List<KnapsackItem> items, long capacity) {
        // Rejects a null list and null elements, guarantees O(1) get(), and
        // shields the computation from concurrent modification by the caller.
        List<KnapsackItem> candidates = List.copyOf(items);

        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        if (candidates.isEmpty()) {
            return KnapsackSolution.empty();
        }

        int rows = candidates.size() + 1;

        // Checked by division: no multiplication is performed, so no product can
        // overflow. Because maxTableCells is an int and rows >= 2, this also
        // proves capacity < Integer.MAX_VALUE, making every cast below safe.
        if (capacity > (long) maxTableCells / rows - 1) {
            throw new ProblemTooLargeException(
                    "Problem too large to solve exactly: %d items with capacity %d exceeds the limit of %d table cells"
                            .formatted(candidates.size(), capacity, maxTableCells));
        }
        requireTotalValueFitsInLong(candidates);

        int columns = (int) capacity + 1;

        // bestValue[i][c]  – greatest total value from the first i items within capacity c
        // bestWeight[i][c] – weight of that solution, used to break ties toward lighter selections
        // taken[i][c]      – whether item i was accepted at that cell
        long[][] bestValue = new long[rows][columns];
        long[][] bestWeight = new long[rows][columns];
        boolean[][] taken = new boolean[rows][columns];

        // Row 0 is the empty selection; long[] is already zero-filled.

        for (int i = 1; i < rows; i++) {
            KnapsackItem item = candidates.get(i - 1);
            long weight = item.weight();
            long value = item.value();

            for (int c = 0; c < columns; c++) {
                long skipValue = bestValue[i - 1][c];
                long skipWeight = bestWeight[i - 1][c];

                if (weight > c) {
                    bestValue[i][c] = skipValue;
                    bestWeight[i][c] = skipWeight;
                    continue;
                }

                int remaining = c - (int) weight;
                long takeValue = value + bestValue[i - 1][remaining];
                long takeWeight = weight + bestWeight[i - 1][remaining];

                // Greater value wins; equal value with less weight wins; otherwise
                // skip. Defaulting to skip resolves full ties toward excluding the
                // later item, which is what the interface contract promises.
                boolean accept = takeValue > skipValue
                        || (takeValue == skipValue && takeWeight < skipWeight);

                bestValue[i][c] = accept ? takeValue : skipValue;
                bestWeight[i][c] = accept ? takeWeight : skipWeight;
                taken[i][c] = accept;
            }
        }

        return reconstruct(candidates, columns - 1, bestValue, bestWeight, taken);
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

    private KnapsackSolution reconstruct(List<KnapsackItem> candidates,
                                         int capacity,
                                         long[][] bestValue,
                                         long[][] bestWeight,
                                         boolean[][] taken) {

        List<Integer> selected = new ArrayList<>();
        int c = capacity;

        for (int i = candidates.size(); i > 0; i--) {
            if (taken[i][c]) {
                KnapsackItem item = candidates.get(i - 1);
                selected.add(item.index());
                c -= (int) item.weight();
            }
        }

        return new KnapsackSolution(
                selected.reversed(),
                bestWeight[candidates.size()][capacity],
                bestValue[candidates.size()][capacity]);
    }
}