# Task: complete the unit test suite for the knapsack solver

## Scope

Work only under `src/test/java/com/arcticblu/subscriptioncapacity/algorithm/`.

**Do not modify anything under `src/main/java`.** If a test appears to reveal a bug in
the solver, leave the production code alone and report the finding instead of fixing it.

Three files are in scope:

1. `DynamicProgrammingKnapsackSolverTest.java` — exists; fix one fixture, add tests
2. `KnapsackItemTest.java` — create
3. `KnapsackSolutionTest.java` — create

## Conventions to follow

- JUnit 6 (`org.junit.jupiter.api`) and AssertJ, both already on the classpath
- Every test carries an `@DisplayName` written as a specification sentence, lowercase,
  describing observable behaviour rather than restating the method name
- Assertions use AssertJ (`assertThat`, `assertThatExceptionOfType`,
  `assertThatNullPointerException`), never JUnit's `Assertions.assertEquals`
- Exception tests assert on message content with `withMessageContaining` so that a
  useless error message fails the build
- Match the existing file's formatting and import ordering

## Part 1 — Fix the existing fixture

`ASSIGNMENT_EXAMPLE` in `DynamicProgrammingKnapsackSolverTest` does not match the
assignment it claims to represent. Replace it with the real data:

```java
// The assignment example: four investors, capacity 15. Investors A and B fill the
// capacity exactly for 320, while the denser but smaller Investor C is a trap.
private static final List<KnapsackItem> ASSIGNMENT_EXAMPLE = List.of(
        new KnapsackItem(0, 5, 120),   // Investor A
        new KnapsackItem(1, 10, 200),  // Investor B
        new KnapsackItem(2, 3, 80),    // Investor C
        new KnapsackItem(3, 8, 160));  // Investor D

private static final long ASSIGNMENT_CAPACITY = 15;
```

Expected results are unchanged: selected indices `[0, 1]`, total value `320`,
total weight `15`, and the greedy-by-density baseline still yields `200`.

Update the explanatory comment in `beatsGreedyOnAssignmentExample`. The densities are
now C = 26.67, A = 24.0, D = 20.0, B = 20.0, so greedy takes C then A (8 of 15 units
consumed, 200 revenue) and neither D nor B still fits.

Keep every existing test. They are correct; only this fixture is wrong.

## Part 2 — Add tests to `DynamicProgrammingKnapsackSolverTest`

### 2.1 Randomised cross-check against exhaustive search (highest priority)

Add a test that runs at least 500 randomised trials, comparing the solver's total value
against a brute-force subset enumeration.

- Seed `java.util.Random` with a fixed constant so failures reproduce
- Each trial: 1–12 items, weights 0–19, values 0–99, capacity 0–39
- Assert the total value equals the brute-force optimum
- Assert the total weight never exceeds the capacity
- Use AssertJ's `.as(...)` to include the trial number, capacity, and item list in the
  failure description, so a failing case can be reconstructed

Write the brute-force helper as a private static method using bitmask enumeration over
all subsets. It returns only the best achievable value, not the selection — the
selection is not uniquely determined and must not be asserted here.

### 2.2 Indices are echoed from the item, not the list position

Every current fixture uses indices that coincide with list positions, so a bug
substituting the loop counter for `item.index()` would pass all of them. Add:

```java
List<KnapsackItem> items = List.of(
        new KnapsackItem(10, 5, 120),
        new KnapsackItem(20, 10, 200),
        new KnapsackItem(30, 3, 80),
        new KnapsackItem(40, 8, 160));
// capacity 15 -> selected indices are exactly [10, 20]
```

### 2.3 A full tie on both value and weight

The existing tie test uses two identical items, where "prefer the earlier item" and
"prefer to exclude the later item" happen to agree. This fixture distinguishes them:

```java
// {0,3} and {1,2} both reach weight 4 for value 10.
List<KnapsackItem> items = List.of(
        new KnapsackItem(0, 1, 1),
        new KnapsackItem(1, 2, 5),
        new KnapsackItem(2, 2, 5),
        new KnapsackItem(3, 3, 9));
// capacity 4 -> selected indices are exactly [1, 2], total value 10
```

Keep the existing two-item tie test as well.

### 2.4 A problem exactly at the table ceiling succeeds

Only rejection is currently tested. The guard is
`capacity > maxTableCells / rows - 1`, so with `maxTableCells = 10` and one item
(`rows = 2`) the largest permitted capacity is 4. Assert that a single item of weight 4
solved at capacity 4 is selected, with no exception.

### 2.5 A non-positive table ceiling is rejected

`new DynamicProgrammingKnapsackSolver(0)` throws `IllegalArgumentException` whose
message mentions `maxTableCells`.

### 2.6 Null inputs are rejected

- `solver.solve(null, 10)` throws `NullPointerException`
- A list containing a null element throws `NullPointerException`. Build it with
  `Arrays.asList(new KnapsackItem(0, 1, 10), null)`, because `List.of` rejects nulls at
  construction and the solver would never be reached.

## Part 3 — Create `KnapsackItemTest`

Cover the compact constructor's validation:

- A negative `index` throws `IllegalArgumentException`
- A negative `weight` throws `IllegalArgumentException`
- A negative `value` throws `IllegalArgumentException`
- Zero index, zero weight, and zero value are all accepted
- A valid item exposes the values it was constructed with

Each exception message should contain the offending value; assert that.

## Part 4 — Create `KnapsackSolutionTest`

Cover the record's contract:

- The index list is copied defensively: construct a solution from a mutable
  `ArrayList`, mutate the original afterwards, and assert the solution is unchanged
- The returned list is unmodifiable: calling `add` on `selectedIndices()` throws
  `UnsupportedOperationException`
- A negative `totalWeight` throws `IllegalArgumentException`
- A negative `totalValue` throws `IllegalArgumentException`
- `KnapsackSolution.empty()` returns an empty list, zero weight, and zero value

## Verification

Run `./mvnw test` and confirm every test passes.

Report anything you could not make pass, and any case where the solver's actual
behaviour differs from what this document predicts. Do not adjust an expected value to
match observed output — a mismatch means either this document or the solver is wrong,
and both possibilities need to be reported rather than silently reconciled.
