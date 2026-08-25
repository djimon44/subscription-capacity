# Testing

This document records the testing strategy and the reasoning behind each choice, 
including the failures found along the way. It is written to be read alongside the test sources 
rather than as a summary of them.

This document explains what the test suite covers, how each test is written, and why it is
written that way. It is meant to be read alongside the code — every excerpt below is quoted
verbatim, from a file under `src/test/java` unless the surrounding text says otherwise.

---

## 1. Overview

The suite has **120 tests** in nine files, falling into three tiers.

| Tier | Files | Tests | Docker | Time |
|---|---|---|---|---|
| Pure unit | `KnapsackItemTest`, `KnapsackSolutionTest`, `DynamicProgrammingKnapsackSolverTest`, `BranchAndBoundKnapsackSolverTest`, `AdaptiveKnapsackSolverTest`, `MinorUnitsTest` | 80 | no | ≈ 0.4 s |
| Unit with mocks | `SubscriptionOptimizationServiceTest` | 17 | no | ≈ 1.3 s |
| Full-stack integration | `SubscriptionCapacityApplicationTests`, `SubscriptionApiIntegrationTest` | 23 | **yes** | ≈ 11 s |

`./mvnw clean test` runs all three tiers and takes about 18 seconds end to end. Most of the
integration cost is one-time: starting the PostgreSQL container and building the Spring
context. Both integration classes share a single container and a single cached context, so
whichever runs second costs ≈ 3 s rather than another ≈ 8 s.

Three of the six pure-unit files are solver tests. That weighting is deliberate: the
knapsack search is the only part of the system with a non-obvious correctness argument, and
it is the part with three implementations that must all agree (§4).

Every file is named `*Test`, so Surefire picks all of them up. Failsafe is not configured in
this project, which is why the full-stack tests are not named `*IT` — an `*IT` class would
silently never run.

### Why the split exists

The three tiers are not redundant, they differ in what a failure *tells you*.

A failing pure unit test localises a bug to a single method. If
`MinorUnitsTest.scalesTwoDecimalPlaces` goes red, the defect is inside
`MinorUnits.toMinorUnits` and nowhere else — there is no HTTP layer, no database, no Spring
context, and no other collaborator that could have caused it.

A failing integration test tells you far less. When
`SubscriptionApiIntegrationTest.acceptsTheBestFittingCombination` goes red, the fault could
be in the controller, the validation annotations, the service, the scaling, the solver, the
JPA mapping, the Flyway migration, the JSON serialisation, or the wiring between any two of
them. That test is worth having because it is the only thing that proves those parts fit
together, but it is a poor diagnostic instrument.

So the strategy is: push as much coverage as possible down into the cheap, precise tier, and
use the expensive tier to prove the seams. That is why, for example, the exhaustive
correctness argument for the knapsack algorithm lives in a pure unit test (§4) while the
integration test checks the assignment example once and moves on.

The middle tier — unit tests with mocked collaborators — exists because
`SubscriptionOptimizationService` has behaviour worth testing that is neither pure
arithmetic nor genuinely integrated: it decides *what to persist* and *what to pass to the
solver*. Mocks let those decisions be inspected directly instead of inferred from a
database row.

---

## 2. The test files

Presented in dependency order: algorithm, then service, then web.

### 2.1 `algorithm/KnapsackItemTest` — 5 tests

`KnapsackItem` is a record with a compact constructor that rejects negative values:

```java
public record KnapsackItem(int index, long weight, long value) {
    public KnapsackItem {
        if (index < 0) { throw new IllegalArgumentException("index must not be negative: " + index); }
        ...
```

A three-field record looks too trivial to test, but the validation is real logic and it is
the guard that keeps nonsense out of the solver's arrays. The file has no fields, no setup,
and no helpers — every test constructs its fixture inline.

| Test | Fixture | Asserts | A failure means |
|---|---|---|---|
| `rejectsNegativeIndex` | `new KnapsackItem(-1, 10, 100)` | `IllegalArgumentException` whose message contains `-1` | the index guard is gone, or its message no longer names the offending value |
| `rejectsNegativeWeight` | `new KnapsackItem(0, -5, 100)` | ditto, containing `-5` | a negative weight could reach the DP loop and index an array backwards |
| `rejectsNegativeValue` | `new KnapsackItem(0, 10, -7)` | ditto, containing `-7` | a negative value could make "take" beat "skip" incorrectly |
| `acceptsZeroes` | `new KnapsackItem(0, 0, 0)` | all three accessors are zero | the guard is off by one and rejects a legitimate boundary value |
| `exposesConstructorArguments` | `new KnapsackItem(7, 250, 12_050)` | accessors return what was passed | the compact constructor mutates its parameters |

The `withMessageContaining("-1")` style is deliberate: it pins that the message names the
bad value, which is what makes the exception useful in a log, without pinning the exact
wording.

`acceptsZeroes` is the interesting one. Zero weight and zero value are legal and the solver
has specific behaviour for them (see `selectsWeightlessItemsAtZeroCapacity` in §2.3), so
this test protects a boundary that a naive `< 1` guard would break.

### 2.2 `algorithm/KnapsackSolutionTest` — 9 tests

`KnapsackSolution` is the solver's return type. Two of its nine tests are about the
defensive copy in its compact constructor, which is the kind of thing that is easy to
delete during a refactor and hard to notice.

```java
@Test
@DisplayName("the index list is copied, so later changes to the caller's list are not visible")
void copiesIndexListDefensively() {
    List<Integer> mutableIndices = new ArrayList<>(List.of(0, 1));

    KnapsackSolution solution = new KnapsackSolution(ALGORITHM, mutableIndices, 15, 320);
    mutableIndices.add(2);
    mutableIndices.clear();

    assertThat(solution.selectedIndices()).containsExactly(0, 1);
}
```

`ALGORITHM` is a file-level constant holding `"DYNAMIC_PROGRAMMING"`; the tests that are not
about the name itself use it so the fixture reads as a solution rather than as a validation
case.

Note the fixture is `new ArrayList<>(List.of(0, 1))`, not `List.of(0, 1)`. An immutable list
could not demonstrate the point — you need a list you can mutate afterwards. Mutating it
twice (`add` then `clear`) is belt and braces: if the record held a reference rather than a
copy, `selectedIndices()` would be empty and the assertion would fail loudly.

`returnsUnmodifiableIndexList` covers the other direction — that a caller cannot mutate the
list they get back — by asserting `UnsupportedOperationException` from
`solution.selectedIndices().add(2)`. Together the two tests establish that the record is
genuinely immutable in both directions, which is what `List.copyOf` buys.

`rejectsNegativeTotalWeight` and `rejectsNegativeTotalValue` mirror the `KnapsackItem`
guards, and `emptySolutionSelectsNothing` pins that the `empty(String)` factory returns an
empty selection with zero totals — the value the service relies on when nothing fits — while
still carrying the name it was given.

**The `algorithmName` component.** The record's first component identifies the algorithm
that produced the result:

```java
public record KnapsackSolution(String algorithmName,
                               List<Integer> selectedIndices,
                               long totalWeight,
                               long totalValue) {
```

It lives on the *solution* rather than on the solver because `AdaptiveKnapsackSolver`
(§2.5) chooses an algorithm per request: one solver no longer means one algorithm, so a
`name()` method on the solver interface could not answer the question honestly. Four tests
cover the validation, which exists because the value is persisted straight into
`optimization_run.algorithm_used`, a `VARCHAR(32) NOT NULL`:

| Test | Fixture | Asserts |
|---|---|---|
| `rejectsNullAlgorithmName` | `null` | `IllegalArgumentException` naming `algorithmName` |
| `rejectsBlankAlgorithmName` | `"   "` | ditto — whitespace is not an identifier |
| `rejectsOverlongAlgorithmName` | `"A".repeat(33)` | `IllegalArgumentException` naming `32` |
| `acceptsAlgorithmNameAtColumnWidth` | `"A".repeat(32)` | accepted, accessor returns it |

The last two are the pair that matters. A length guard is exactly the kind of thing that
gets written as `>= 32` by accident, and the boundary test is what distinguishes "rejects
what the column cannot hold" from "rejects one character too early". Catching an overlong
name here turns a `DataIntegrityViolationException` from PostgreSQL — thrown deep inside a
flush, after the solver has already done its work — into an immediate, clearly worded
failure at the point of construction.

### 2.3 `algorithm/DynamicProgrammingKnapsackSolverTest` — 25 tests

The largest and most important unit test file. It covers the exact 0/1 knapsack solver.

**Setup.** Two constants control the randomised cross-check (§4), and the solver under test
is a plain field — no mocks, no Spring, nothing to inject:

```java
private static final int RANDOM_TRIALS = 500;
private static final long RANDOM_SEED = 20_250_821L;

private final DynamicProgrammingKnapsackSolver solver = new DynamicProgrammingKnapsackSolver();
```

The no-arg constructor uses `DEFAULT_MAX_TABLE_CELLS = 10_000_000`, which `application.yml`
now matches with `max-table-cells: 10000000` — roughly 170 MB per request at 17 bytes per
cell. Either way the default is far too large to reach by hand, so the two ceiling tests
construct their own bounded solvers rather than relying on it.

The shared fixture is the assignment example, with a comment explaining what makes it a good
test case:

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

There are three private helpers: `bruteForceBestValue` (§4), `assertEmptySolution`, and
`greedyByDensity`.

**Correctness on the worked example.** `solvesAssignmentExample` asserts the full triple —
indices `0, 1`, value `320`, weight `15`.

`beatsGreedyOnAssignmentExample` is the one that justifies the whole algorithm choice. It
runs a greedy value-per-weight heuristic over the same fixture and shows it loses:

```java
long greedyValue = greedyByDensity(ASSIGNMENT_EXAMPLE, ASSIGNMENT_CAPACITY);

// Densities are C = 26.67, A = 24.0, D = 20.0, B = 20.0, so density takes C
// then A -- 8 of 15 units consumed for 200 revenue -- after which neither D
// nor B still fits.
assertThat(greedyValue).isEqualTo(200);

KnapsackSolution solution = solver.solve(ASSIGNMENT_EXAMPLE, ASSIGNMENT_CAPACITY);

assertThat(solution.totalValue()).isEqualTo(320);
assertThat(solution.totalValue()).isGreaterThan(greedyValue);
```

Asserting `greedyValue == 200` first is what makes this test honest: it pins that the
heuristic really was implemented and really did produce its best answer, so the comparison
is not accidentally vacuous. The helper compares densities by cross-multiplication rather
than division to stay in exact integer arithmetic, and its Javadoc is explicit that this
assumes positive weights and modest values.

**Index handling.** `echoesItemIndicesRatherThanListPositions` uses items with indices
`10, 20, 30, 40` in list positions `0..3` and asserts the solution reports `10, 20`. The
solver's `reconstruct` walks the DP table by list position but records `item.index()`; this
test is what stops someone "simplifying" that to `i - 1`. It matters downstream, because
`SubscriptionOptimizationService` uses those indices to decide which investor was accepted.

**Boundaries and degenerate inputs.**

| Test | Fixture | Expectation |
|---|---|---|
| `returnsEmptySolutionForNoItems` | `List.of()`, capacity 100 | empty solution |
| `returnsEmptySolutionForZeroCapacity` | one item of weight 1, capacity 0 | empty solution |
| `selectsWeightlessItemsAtZeroCapacity` | weights 0 and 1, capacity 0 | selects index 0 only, weight 0, value 40 |
| `returnsEmptySolutionWhenNothingFits` | weights 10 and 20, capacity 5 | empty solution |
| `selectsSingleExactFit` | weight 42, capacity 42 | selects it, weight 42 |
| `alwaysSelectsWeightlessItemWithValue` | weights 0 and 7, capacity 5 | selects the weightless one |
| `selectsAllItemsWhenTheyAllFit` | weights 10/20/30, capacity 100 | all three, weight 60 |

The two weightless-item tests are the sharp ones. A zero-weight item with value must always
be taken, even at zero capacity, and it is easy to write a `weight > c` guard that
accidentally excludes it. `assertEmptySolution` factors out the three-assertion check the
empty cases share.

**Tie-breaking.** The solver interface promises a specific tie-break order, so three tests
pin it:

- `breaksValueTiesTowardTheLighterSolution` — two items worth 100, weights 10 and 4,
  capacity 10. Expects index 1, weight 4.
- `breaksRemainingTiesTowardTheEarlierItem` — two identical items, capacity 5. Expects
  index 0.
- `breaksFullTiesBetweenDistinctSubsets` — the subtle case, with its own comment:

```java
// {0,3} and {1,2} both reach weight 4 for value 10, so "prefer the earlier
// item" and "prefer to exclude the later item" pull in opposite directions.
```

That third test documents which of two conflicting rules actually wins. Without it, the
answer is only discoverable by reading the DP inner loop.

**Minor units.** `sumsMinorUnitValuesExactly` runs the solver on values already scaled to
cents (`12_050` and `9_975`) and asserts the total is exactly `22_025`. It is a
characterisation test for the design decision that the solver works in integers: if anyone
converted the solver to `double`, `120.50 + 99.75` would stop landing exactly on `220.25`.

**Limits and failure modes.**

- `rejectsOversizedProblem` — a solver bounded at 100 cells, asked for capacity 1000.
  Asserts `ProblemTooLargeException` containing `"capacity 1000"` and `"100 table cells"`.
- `solvesProblemExactlyOnTableCeiling` — a solver bounded at 10 cells with a capacity of 4,
  which is exactly `2 rows × 5 columns = 10`. Asserts it still solves. This is the
  off-by-one partner to the test above; together they pin the boundary from both sides.
- `rejectsNonPositiveTableCeiling` — `new DynamicProgrammingKnapsackSolver(0)` throws.
- `rejectsNullItemList` / `rejectsNullItemInsideList` — both expect `NullPointerException`,
  which comes from the `List.copyOf(items)` on the solver's first line. The second has a
  comment worth keeping:

```java
// Arrays.asList permits the null that List.of would reject outright,
// so the solver is genuinely the thing under test here.
List<KnapsackItem> items = Arrays.asList(new KnapsackItem(0, 1, 10), null);
```

  Without `Arrays.asList`, the fixture itself would throw and the test would pass while
  testing nothing.

- `rejectsCapacityNearLongMaxValue` — capacity `Long.MAX_VALUE`. The solver's guard is
  written as a division (`capacity > (long) maxTableCells / rows - 1`) precisely so that no
  multiplication can overflow; this test pins that it fails fast rather than allocating.
- `rejectsTotalValueOverflow` — values `Long.MAX_VALUE` and `1`, expecting
  `ProblemTooLargeException` containing `"Combined item value"`. This covers the separate
  `requireTotalValueFitsInLong` pre-check.
- `rejectsNegativeCapacity` — `IllegalArgumentException` containing `-1`.

**The algorithm name.** Two tests pin that the solver stamps its own name onto what it
returns: `solutionNamesTheAlgorithm` asserts `"DYNAMIC_PROGRAMMING"` on a solved problem,
and `emptySolutionNamesTheAlgorithm` asserts the same on the empty-list path. The second is
not redundant — the empty path returns through `KnapsackSolution.empty(ALGORITHM_NAME)`
rather than the reconstruction step, so it is a separate construction site that could be
left unnamed.

The 25th test is `matchesBruteForceAcrossRandomisedProblems`, covered in §4.

### 2.4 `algorithm/BranchAndBoundKnapsackSolverTest` — 19 tests

`BranchAndBoundKnapsackSolver` solves the same problem as the dynamic programming solver by
a completely different route: a depth-first search over include/exclude decisions, pruned by
a fractional-relaxation upper bound. Its cost depends on the *number of items* rather than
the *magnitude of the capacity*, which is why it exists — a capacity of 5,000,000,000 minor
units has no DP table small enough to allocate, and no effect at all on this search.

**Setup.** Two solvers are fields, because most of this file's value comes from comparing
them:

```java
private final KnapsackSolver solver = new BranchAndBoundKnapsackSolver();
private final KnapsackSolver referenceSolver = new DynamicProgrammingKnapsackSolver();
```

Note both are typed as the interface. Nothing in this file reaches for an implementation
detail, so the tests would keep compiling if either class were replaced.

The fixture is the same assignment example used in §2.3, deliberately: the two solvers are
asked the identical question, so a discrepancy is visible at a glance.

**Correctness and equivalence.**

| Test | Fixture | Expectation |
|---|---|---|
| `solvesAssignmentExample` | the four investors, capacity 15 | indices `0, 1`, value 320, weight 15 |
| `returnsOriginalItemIndices` | indices `10, 20, 30, 40` | reports `10, 20` |
| `solvesVeryLargeCapacity` | weights near 10⁹, capacity 5 × 10⁹ | value 80,000 |
| `alwaysSelectsWeightlessItemWithValue` | weights 0 and 7, capacity 5 | selects the weightless one |
| `returnsEmptySolutionForNoItems` / `ForZeroCapacity` / `WhenNothingFits` | degenerate inputs | empty solution |
| `rejectsNegativeCapacity` | capacity `-1` | `IllegalArgumentException` naming `-1` |
| `rejectsTotalValueOverflow` | values `Long.MAX_VALUE` and `1` | `ProblemTooLargeException` |

`returnsOriginalItemIndices` is sharper here than its DP counterpart. This solver *sorts*
its items by descending value density before searching, so list position and reported index
diverge by construction:

```java
// Densities are 26.67, 24.0, 20.0, 20.0, so the search reorders these entirely.
```

If the search ever reported its own sorted position, every downstream investor mapping would
be silently wrong. The DP solver cannot make that mistake, because it never reorders.

`solvesVeryLargeCapacity` is the test that justifies the class. Its comment records the
arithmetic — a DP table for that capacity would need roughly 5 × 10⁹ columns — and the
assertion shows the search returns the right answer regardless. This is the capability the
adaptive solver (§2.5) exists to reach.

**The algorithm name.** `solutionNamesTheAlgorithm` and `emptySolutionNamesTheAlgorithm`
assert `"BRANCH_AND_BOUND"` on a solved problem and on the empty-list path respectively.
These replaced an earlier test that asserted `solver.name()`; when the name moved onto the
solution, the assertion had to move with it, and the empty path needed its own case for the
reason given in §2.3.

**Density arithmetic that must not refuse a valid request.** Ordering by density
cross-multiplies value by weight, and that product overflows a `long` well inside the
limits the API documents — around $215,000,000 per candidate at a 2% fee.
`solvesCandidatesWhoseDensityProductOverflowsALong` uses two candidates of 25,000,000,000
minor units against fees of 500,000,000, whose cross product is 1.25 × 10¹⁹, and asserts a
solution rather than a `ProblemTooLargeException`. What that solution should be is not
asserted by hand: the same problem is divided through by a million, which brings it inside
a DP table, and the reference solver's answer is scaled back up. The comparison is therefore
against an independent implementation rather than against arithmetic done in the test.

`solvesWhenTheUpperBoundArithmeticOverflows` covers the other overflow site. The bound's
partial-item term is `value × remaining`, which is 10²⁸ for that fixture. Overflowing it
yields `Long.MAX_VALUE`, an overestimate, which prunes nothing and so cannot change the
answer; the test asserts the exact optimum comes back anyway. An underestimate would be the
dangerous direction, and no assertion on "no exception thrown" alone would have caught it.

**The node limit.** Pruning is weakest when candidates share a value density, which is
exactly what a flat percentage fee schedule produces, so `flatFeeCandidates` builds that
worst case directly: 18 candidates whose fee is twice their amount.

| Test | Solver | Expectation |
|---|---|---|
| `smallProblemIsUnaffectedByTheNodeLimit` | default limit | agrees with dynamic programming |
| `refusesProblemThatExceedsTheConfiguredNodeLimit` | limit 100 | `ProblemTooLargeException` |
| `refusalNamesTheLimitAndTheItemCount` | limit 100 | message names `100` and `18 items` |
| `rejectsNonPositiveNodeLimit` | limit 0 | `IllegalArgumentException` |

The first two are a matched pair on one fixture, and only the pair proves anything: the
refusal test alone would pass against a solver that refused everything, so it first asserts
the default solver solves the identical problem. A node count rather than a wall-clock
timeout is what makes this testable at all — a 100-node ceiling fires at the same point on
every machine and under any load, where a millisecond timeout would be a flake.

**The two cross-solver property tests** — `agreesWithDynamicProgramming` and
`selectsSameSubsetAsDynamicProgramming`, 1,000 trials each — are covered in §4. They are the
reason this file is worth its length.

### 2.5 `algorithm/AdaptiveKnapsackSolverTest` — 10 tests

`AdaptiveKnapsackSolver` owns no search of its own. It holds one instance of each of the
other two solvers and answers one question per request: *does the dynamic programming table
for this problem fit the configured ceiling?* If it does, DP runs; if it does not, branch and
bound runs. The consequence for callers is that a large capacity is no longer an error.

The routing test is a copy of the guard inside the DP solver, written as a division so no
product can overflow:

```java
int rows = items.size() + 1;
return capacity <= (long) maxTableCells / rows - 1;
```

That duplication is the risk this file exists to manage. If the two conditions ever drift
apart, the adaptive solver will route a problem to DP that DP then refuses, and a
`ProblemTooLargeException` will reach a caller who was promised it could not.

**Routing.** Four tests pin the decision, three of them against a deliberately tiny ceiling
so the boundary is reachable by hand:

| Test | Solver | Problem | Expected `algorithmName` |
|---|---|---|---|
| `solvesSmallProblemWithDynamicProgramming` | default | assignment example, capacity 15 | `DYNAMIC_PROGRAMMING` |
| `staysOnDynamicProgrammingAtTheCeiling` | `new AdaptiveKnapsackSolver(10)` | one item, capacity 4 | `DYNAMIC_PROGRAMMING` |
| `fallsThroughToBranchAndBoundPastTheCeiling` | `new AdaptiveKnapsackSolver(10)` | one item, capacity 5 | `BRANCH_AND_BOUND` |
| `ceilingTightensAsItemsAreAdded` | `new AdaptiveKnapsackSolver(30)` | capacity 14 with 1 item, then 5 | `DYNAMIC_PROGRAMMING`, then `BRANCH_AND_BOUND` |

The middle two are a matched pair on either side of the same boundary — 2 rows × 5 columns
is exactly the 10-cell ceiling, and one more unit of capacity is one column too many. Either
test alone would pass against an off-by-one; together they pin the boundary exactly, and
`staysOnDynamicProgrammingAtTheCeiling` deliberately mirrors
`solvesProblemExactlyOnTableCeiling` in §2.3 so the two sides of the duplicated condition are
tested at the same point.

`ceilingTightensAsItemsAreAdded` covers the part of the formula the other three hold
constant. The ceiling is not a capacity limit but a *cell* limit, so adding items lowers it:
at 30 cells one item allows a capacity of 14, five items allow only 4. The same capacity
therefore routes differently depending on how many candidates applied — a property that a
test fixing the item count could not see.

Every routing test asserts the selected indices and totals as well as the name. The name
alone would prove which branch ran, not that the branch returned the right answer.

**No longer an error.**

```java
assertThatNoException().isThrownBy(() -> solver.solve(items, capacity));
```

`solvesCapacityBeyondAnyFeasibleTable` states the user-visible promise directly: a capacity
of 5 × 10⁹ minor units, far past any allocatable table, is *solved* rather than refused.
Before the adaptive solver, this exact input produced `ProblemTooLargeException`, which
`GlobalExceptionHandler.handleProblemTooLarge` maps to a `400` from the API. `solvesCapacityAtTheTopOfTheLongRange` pushes it to `Long.MAX_VALUE`, which is
the input most likely to overflow a carelessly written fit check; it asserts a correct answer
rather than merely the absence of a crash.

`returnsEmptySolutionForNoItems` asserts the empty triple and that the name `isNotBlank()`,
rather than naming a specific algorithm. That is deliberate: which solver handles an empty
list is an implementation detail of the routing, and pinning it would make the test fail on a
legitimate change. What must hold is that *some* algorithm is recorded, because the column is
`NOT NULL`.

`rejectsNegativeCapacity` and `rejectsNonPositiveTableCeiling` confirm the delegating solver
does not swallow the guards its delegates provide.

**The randomised cross-check** — `matchesDynamicProgrammingAcrossRandomisedProblems`, 500
trials — is covered in §4, and is the most important test in the file.

### 2.6 `service/MinorUnitsTest` — 12 tests

`MinorUnits` converts between `BigDecimal` currency amounts and the `long` minor units the
solver operates on. This is where money bugs live, so it gets its own file.

**Why the test is in the `service` package.** `MinorUnits` is package-private:

```java
final class MinorUnits {
```

so the test class must sit in `com.arcticblu.subscriptioncapacity.service` to see it. That
is a normal Java testing arrangement — the test source tree mirrors the main one, and the
two halves of a package are merged on the classpath at test time.

The file has no fields and no setup. Every test calls the static methods directly.

**Scaling, `toMinorUnits`.**

| Test | Input | Expected |
|---|---|---|
| `scalesWholeAmount` | `new BigDecimal("15")` | `1500` |
| `scalesTwoDecimalPlaces` | `new BigDecimal("15.75")` | `1575` |
| `scalesTrailingZeroInSecondPlace` | `new BigDecimal("5.10")` | `510` |
| `stripsTrailingZerosBeforeCheckingScale` | `new BigDecimal("5.100")` | `510` |
| `scalesZero` | `BigDecimal.ZERO` | `0` |
| `scalesSmallestAmount` | `new BigDecimal("0.01")` | `1` |

`stripsTrailingZerosBeforeCheckingScale` is the reason the production code calls
`stripTrailingZeros()` before it checks the scale. `new BigDecimal("5.100")` has a scale of
3, which would trip the "at most 2 decimal places" guard even though the third place is a
zero and carries no information. A caller sending `5.100` means five units and ten cents,
and should not get a 400.

The sixth scaling test is the one that repays the most explanation:

```java
@Test
@DisplayName("an amount whose stripped form carries a negative scale still scales correctly")
void scalesValueWithNegativeScaleAfterStripping() {
    // stripTrailingZeros() turns 100 into 1E+2, a scale of -2; movePointRight accepts
    // that and this pins that the answer is still right.
    assertThat(new BigDecimal("100").stripTrailingZeros().scale()).isEqualTo(-2);
    assertThat(MinorUnits.toMinorUnits(new BigDecimal("100"), "amount")).isEqualTo(10_000L);
}
```

`stripTrailingZeros()` on `100` does not give you `100` — it gives you `1E+2`, whose scale
is **−2**. That is legal, `movePointRight(2)` handles it, and the answer is still `10000`.
The first assertion documents the surprising intermediate value so a reader does not have to
discover it themselves; the second pins the outcome. This is a case where the test doubles
as documentation of a `BigDecimal` gotcha.

**Rejections.** Both expect `InvalidSubscriptionInputException`.

```java
assertThatExceptionOfType(InvalidSubscriptionInputException.class)
        .isThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("5.123"), "requestedAmount"))
        .withMessageContaining("requestedAmount")
        .withMessageContaining("5.123");
```

`rejectsExcessPrecision` asserts the message names **both** the field and the value, because
`toMinorUnits` takes the field name as a parameter precisely so the resulting 400 can tell a
client which input was wrong. `rejectsOverflowingAmount` uses `99999999999999999` — 17 nines,
which becomes 19 digits after scaling and no longer fits in a `long` — and asserts the
message mentions the field and `"too large"`. That path is a caught `ArithmeticException`
from `longValueExact()`, translated into a client-facing error rather than a 500.

**Converting back, `toDecimal`.**

```java
BigDecimal amount = MinorUnits.toDecimal(1575L);

assertThat(amount).isEqualByComparingTo("15.75");
assertThat(amount.scale()).isEqualTo(2);
```

Two assertions, deliberately separate. `isEqualByComparingTo` checks numeric value and
ignores scale; `scale()` is then asserted on its own because here the scale *is* the point —
the API must render `15.75`, not `15.750` or `1.575E+1`. `convertsZeroBackToDecimal` does
the same for `0.00`.

`roundTripPreservesValue` bundles three round trips into one test — `15.75`, `0.01`, and
`100` — each asserting `toDecimal(toMinorUnits(x))` compares equal to `x`. The `100` case is
there to close the negative-scale loop from earlier.

### 2.7 `service/SubscriptionOptimizationServiceTest` — 17 tests

A plain Mockito test with no Spring context. This is the tier that checks *orchestration*:
what the service passes to the solver, what it builds to persist, and what it returns.

**Setup.**

```java
private static final Instant FIXED_NOW = Instant.parse("2026-06-01T10:00:00Z");
private static final String SOLUTION_ALGORITHM_NAME = "TEST_ALGORITHM";

/** The assignment example: A and B together fill a capacity of 15 for a fee of 320. */
private static final KnapsackSolution ASSIGNMENT_SOLUTION =
        new KnapsackSolution(SOLUTION_ALGORITHM_NAME, List.of(0, 1), 1500L, 32000L);

@Mock
private KnapsackSolver solver;

@Mock
private OptimizationRunRepository runRepository;

@Mock
private PlatformTransactionManager transactionManager;

private SubscriptionOptimizationService service;

@BeforeEach
void createService() {
    service = new SubscriptionOptimizationService(
            solver, runRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), transactionManager);
}
```

The service is constructed by hand. Nothing here needs a Spring context: the class has one
constructor and four collaborators, so `new` is faster, more explicit, and cannot break
because of an unrelated bean. The fourth of those collaborators is a transaction manager —
see §5.8 for why the service takes one at all.

Note that `ASSIGNMENT_SOLUTION` is a *canned* answer. This file does not test whether
`(0, 1)` is the right selection — `DynamicProgrammingKnapsackSolverTest` did that. Here the
solver is a stub whose only job is to return something predictable so the service's handling
of it can be examined.

**The stubbing helper.** Three stubs are needed together, so they live in one method:

```java
private void stubSolver(KnapsackSolution solution) {
    when(solver.solve(anyList(), anyLong())).thenReturn(solution);
    // The write runs inside a TransactionTemplate, which asks the manager for a
    // status and hands it to the callback; a bare mock would supply null.
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    // A default mock returns null and the service maps the saved entity into its
    // response, so save() must hand back what it was given.
    when(runRepository.save(any(OptimizationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
}
```

Both comments record a trap. An unstubbed mock method returns the type's default — `null`
for objects — and either null causes a confusing failure a long way from its cause. `save()`
returning `null` produces a `NullPointerException` when the service maps the result, and
`getTransaction` returning `null` hands a null `TransactionStatus` to the callback, which is
tolerable today but would break the moment the callback consults it. See §3 for the general
technique and §5.8 for the transaction manager.

The helper is called explicitly by each test rather than run from `@BeforeEach`, because
`MockitoExtension` defaults to strict stubs and would fail the one test that never reaches
the solver.

**The three captor helpers.**

```java
private List<KnapsackItem> capturedItems() {
    ArgumentCaptor<List<KnapsackItem>> items = ArgumentCaptor.captor();
    verify(solver).solve(items.capture(), anyLong());
    return items.getValue();
}

private long capturedCapacity() { ... }

private OptimizationRun capturedRun() { ... }
```

`ArgumentCaptor.captor()` is the modern factory (Mockito 5.7+); it infers the generic type,
avoiding the unchecked cast that `ArgumentCaptor.forClass(List.class)` forces. Calling
`verify` more than once in a test is harmless — the default `times(1)` re-checks the same
invocation count rather than consuming it — which is what lets `scalesAmountsToMinorUnits`
call both `capturedItems()` and `capturedCapacity()`.

**Fixtures.** `assignmentExample()` builds the four-investor `OptimizeRequest`.
`storedRun(UUID)` builds an `OptimizationRun` with four `SubscriptionRequest` children, two
accepted, and carries this note:

```java
/** A run as it comes back from the repository; ids of never-persisted children stay null. */
```

That is worth knowing: `SubscriptionRequest.equals` returns `false` when `id` is null, so
these fixtures must not be compared by equality or put in a `HashSet`. None of the
assertions do.

**The `optimize` cases (14).**

1. `scalesAmountsToMinorUnits` — captures the item list and asserts weights
   `500, 1000, 300, 800` and values `12_000, 20_000, 8_000, 16_000`, and that the captured
   capacity is `1500`. A failure means the service handed the solver decimal or wrongly
   scaled numbers, which would corrupt every result silently.
2. `scalesFractionalAmountsExactly` — same shape with `maxCapacity` `15.75` and an amount of
   `5.25`; asserts `1575` and `525`. Covers the fractional path that case 1's whole numbers
   cannot.
3. `itemIndicesMatchInputPositions` — captured indices are `0, 1, 2, 3`. If this fails, the
   solver's answer would be mapped back onto the wrong investors.
4. `persistsEveryCandidate` — the captured run holds four subscriptions, not two. This is
   the audit-trail requirement: rejected applicants must be recorded too.
5. `marksOnlySelectedCandidatesAccepted` — `containsExactly(true, true, false, false)`.
6. `preservesInputOrder` — asserts both `inputIndex` `0, 1, 2, 3` and the investor names in
   order, so a stable-but-wrong permutation cannot pass.
7. `recordsBothCountsFromTheirOwnSource` — `acceptedCount` 2, `candidateCount` 4. A
   regression test; see §6.
8. `recordsTheAlgorithmNameFromTheSolution` — stubs a solution carrying
   `"NOT_A_REAL_ALGORITHM"` and asserts `getAlgorithmUsed()` reports exactly that. The value
   is not a plausible algorithm name on purpose: it cannot coincidentally match a hardcoded
   constant in production code, and its obvious fakeness announces to the next reader that
   the point of the test is *provenance*, not the name itself. See §5.7 and §6.
9. `recordsTheAlgorithmNameOfEachRunSeparately` — the same assertion with
   `"BRANCH_AND_BOUND"`, a name a real solver could return. Together with case 8 it pins that
   the recorded name tracks the solution rather than being fixed once: the service reads it
   per run, which is what an adaptive solver requires.
10. `convertsTotalsBackFromMinorUnits` — `15.00` and `320.00` by `isEqualByComparingTo`, from
   the solver's `1500` and `32000`.
11. `timestampsFromTheInjectedClock` — `getCreatedAt()` equals `FIXED_NOW` exactly. Only
    possible because the clock is injected; see §3.
12. `responseReportsAcceptedSubscriptionsOnly` — the returned DTO names exactly Investor A
    and Investor B. Distinct from case 4: the *run* holds four, the *response* shows two.
13. `persistsRunWhenNothingIsSelected` — stubs `KnapsackSolution.empty(…)` and asserts the
    response is empty with zero totals **and** that the run is still saved with four
    subscriptions, all rejected, `acceptedCount` 0. "Nothing fits" is a successful run, not
    an error.
14. `refusesExcessPrecisionBeforeSolving` — passes `5.123` and asserts
    `InvalidSubscriptionInputException`, then:

```java
verify(solver, never()).solve(anyList(), anyLong());
verifyNoInteractions(runRepository);
```

    The exception alone would not prove the ordering. These two lines are what pin that
    validation happens *before* any expensive or persistent work.

**The `findByRequestId` cases (2).** `returnsStoredRunWithAcceptedSubscriptions` stubs
`findByIdWithSubscriptions` with `Optional.of(storedRun(requestId))` and asserts the mapped
response — id, the two accepted names, both totals, and `createdAt`.
`failsWhenRunIsUnknown` stubs `Optional.empty()` and asserts
`OptimizationRunNotFoundException` `.withMessageContaining(requestId.toString())`, so the
error names what was actually asked for.

**The `findAll` case (1).** The most technically interesting test in the file:

```java
UUID requestId = UUID.randomUUID();
OptimizationRun run = spy(storedRun(requestId));
Page<OptimizationRun> page = new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1);
when(runRepository.findAllByOrderByCreatedAtDescIdDesc(any())).thenReturn(page);

PagedResponse<OptimizationRunSummary> response = service.findAll(PageRequest.of(0, 20));
...
verify(run, never()).getSubscriptions();
```

It checks the pagination envelope is mapped faithfully (page 0, size 20, 1 element, 1 page),
that the summary's counts come from the run's stored fields, and — via the spy — that
building the summary never touches the subscriptions association. See §5.4 for why that last
line is the point of the test.

### 2.8 Integration support: `TestcontainersConfiguration` and friends

Three small files in the root test package.

`TestcontainersConfiguration` declares the database container as a Spring bean:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}
}
```

`@TestConfiguration` marks it as opt-in — it is not picked up by component scanning and
applies only where it is explicitly `@Import`ed. `@ServiceConnection` is what removes the
usual boilerplate; see §3.

Two notes on this file. It is `public` so that `SubscriptionApiIntegrationTest`, which lives
in the `web` subpackage, can name it in `@Import`. And the image is pinned to
`postgres:16-alpine`, the same tag `docker-compose.yml` runs. A floating `postgres:latest`
would let the database version drift under the suite with no change to the repository, and
would validate the schema against a version the application never runs on.

`SubscriptionCapacityApplicationTests` is the single smoke test:

```java
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SubscriptionCapacityApplicationTests {

	@Test
	void contextLoads() {
	}
}
```

An empty test body is not a placeholder here — the assertion *is* that the context started.
It proves every bean resolves, the Flyway migrations apply cleanly against a real
PostgreSQL, and `ddl-auto: validate` finds the JPA mappings consistent with the migrated
schema. When it fails, the whole application is broken at startup.

`TestSubscriptionCapacityApplication` is not a test at all. It is a `main` that launches the
real application with the container attached, for running the app locally without installing
PostgreSQL:

```java
SpringApplication.from(SubscriptionCapacityApplication::main).with(TestcontainersConfiguration.class).run(args);
```

### 2.9 `web/SubscriptionApiIntegrationTest` — 22 tests

The full stack: real HTTP over a real port, a real Spring context, and a real PostgreSQL
container. Nothing is mocked.

**Setup.**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class SubscriptionApiIntegrationTest {
```

Path constants, three fixed timestamps, and two `ParameterizedTypeReference` constants
needed because generic response types are erased at runtime:

```java
// An hour apart, so no clock resolution or tie-break can reorder them.
private static final Instant TEN_O_CLOCK = Instant.parse("2026-06-01T10:00:00Z");
private static final Instant ELEVEN_O_CLOCK = Instant.parse("2026-06-01T11:00:00Z");
private static final Instant TWELVE_O_CLOCK = Instant.parse("2026-06-01T12:00:00Z");

private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
        new ParameterizedTypeReference<>() {
        };

private static final ParameterizedTypeReference<PagedResponse<OptimizationRunSummary>> LISTING =
        new ParameterizedTypeReference<>() {
        };
```

Two injected collaborators and the cleanup hook:

```java
@Autowired
RestTestClient client;

@Autowired
OptimizationRunRepository runRepository;

// The server serves requests on its own threads, so a test-managed transaction would
// not cover them; state is cleared outright instead of rolled back.
@BeforeEach
void clearRuns() {
    runRepository.deleteAll();
}
```

See §5.2 for why rollback is not used.

**Helpers.** `optimize(OptimizeRequest)` performs the POST and unwraps a 201 body;
`listing(String uri)` performs the GET and unwraps the paged envelope;
`expectValidationFailure(OptimizeRequest)` performs a POST expected to fail and returns the
problem document. `errorFields(Map)` pulls the `field` values out of the `errors` array —
the one place the test needs an unchecked cast, marked `@SuppressWarnings("unchecked")`.
`assignmentExample()` builds the four-investor request. `storeRun(Instant, BigDecimal)`
writes a run straight to the database; see §5.3.

**The create path (7 tests).**

1. `acceptsTheBestFittingCombination` — the assignment example over HTTP: exactly Investor A
   and Investor B accepted, totals `15.00` and `320.00`.
2. `advertisesTheLocationOfTheCreatedRun` — asserts the `Location` header:

```java
assertThat(result.getResponseHeaders().getLocation())
        .hasToString("http://localhost:%d/api/v1/subscriptions/%s"
                .formatted(result.getUrl().getPort(), requestId));
```

   The port is read back from the request because `RANDOM_PORT` means it is not known until
   runtime. The substance of the assertion is the path and the id.

3. `rendersAmountsAtTwoDecimalPlaces` — an input of `5` comes back as `"5.00"`, asserted via
   `toPlainString()` on the deserialised `BigDecimal`. Jackson builds a `BigDecimal` from the
   literal JSON token, so a scale of 2 in the parsed value does establish two decimal places
   on the wire.
4. `readsBackExactlyWhatTheCreateReturned` — POST then GET, asserting whole-record equality.
   See §5.5.
5. `persistsDeclinedCandidatesToo` — goes around the API to the repository and asserts four
   subscriptions, with A and B accepted and C and D rejected. The API never exposes the
   rejected candidates, so this is the only way to verify the audit trail actually landed in
   the database.
6. `succeedsWithNothingAcceptedWhenNothingFits` — capacity 1, one candidate needing 5.
   Expects **201**, empty list, zero totals. Pins the deliberate decision that an empty
   result is a successful run.
7. `keepsFractionalAmountsIntact` — `15.75` capacity with amounts `5.25` and `10.50`, fees
   `120.50` and `200.25`; asserts the amounts come back as `"5.25"` and `"10.50"` and the
   fee total as `320.75`.

**Rejections (7 tests).** All assert status 400 and content type
`application/problem+json` — the RFC 9457 media type — and all but the last two check the
document body.

| Test | Input | Asserted `field` / `title` |
|---|---|---|
| `refusesNegativeCapacity` | `maxCapacity` `-1` | field `maxCapacity` |
| `refusesEmptyCandidateList` | `List.of()` | field `availableSubscriptions` |
| `refusesAmountFinerThanAMinorUnit` | amount `5.123` | field `availableSubscriptions[0].requestedAmount` |
| `refusesBlankInvestorName` | name `"   "` | field `availableSubscriptions[0].investorName` |
| `refusesMalformedJson` | truncated JSON literal | title `Malformed request body` |
| `reportsUnknownRunAsNotFound` | random UUID, 404 | title `Optimization run not found` |
| `refusesIdentifierThatIsNotAUuid` | `"not-a-uuid"` | status and content type only |

The indexed field path in `refusesAmountFinerThanAMinorUnit` is the valuable detail: it
proves `@Valid` cascades into the list elements and that the error names *which* candidate
was wrong, which is what makes the response actionable. `refusesBlankInvestorName` uses
three spaces rather than an empty string, so it exercises `@NotBlank` rather than
`@NotEmpty`. `refusesMalformedJson` sends a deliberately truncated body:

```java
.body("{\"maxCapacity\": 15, \"availableSubscriptions\": [")
```

**Listing and pagination (5 tests).**

- `listsRunsNewestFirst` — three runs written directly at 10:00, 11:00 and 12:00 with
  capacities 10, 20 and 30. Asserts `totalElements` 3, capacities in the order
  `"30.00", "20.00", "10.00"`, and timestamps `containsExactly(TWELVE, ELEVEN, TEN)`.
- `listingReportsBothCounts` — via the API, `acceptedCount` 2 and `candidateCount` 4.
- `capsOversizedPageRequests` — `?size=500` returns `size` 100, the
  `spring.data.web.pageable.max-page-size` configured in `application.yml`.
- `slicesResultsIntoPages` — `?page=0&size=2` over three stored runs gives 2 items,
  `totalPages` 2, `totalElements` 3.
- `ignoresUnrecognisedSortValue` — `?sort=doesNotExist` returns **200** with the usual
  newest-first order. The listing query names its own ordering, so the resolved `Sort` is
  discarded in the controller; forwarding it would let a caller-supplied property name
  reach Spring Data, raise `PropertyReferenceException`, and come back as a 500. The test
  asserts the ordering rather than only the status, so it also pins that discarding the
  sort did not disturb the ordering the endpoint promises.

**Dispatch failures (3 tests).** Spring raises these before any controller method runs, and
each asserts both the status and the `application/problem+json` body.

| Test | Request | Expected |
|---|---|---|
| `reportsUnknownPathAsNotFound` | `GET /api/v1/no-such-resource` | 404 |
| `reportsUnsupportedMethodAsMethodNotAllowed` | `DELETE` on the collection | 405 |
| `reportsUnsupportedContentTypeAsUnsupportedMediaType` | form-encoded `POST` to optimize | 415 |

All three previously returned 500 with a full stack trace logged at ERROR, because
`@ExceptionHandler(Exception.class)` caught them. That made an unauthenticated request loop
a log-flooding primitive as well as a wrong answer. `GlobalExceptionHandler` now extends
`ResponseEntityExceptionHandler`, whose handlers produce the correct status and an RFC 9457
body without logging; the catch-all is left for genuine defects.

---

## 3. Techniques used

### AssertJ, and why `isEqualByComparingTo` is mandatory for `BigDecimal`

AssertJ assertions read as one chained sentence starting from `assertThat(...)`, and the
failure message is generated from the chain, so `containsExactly` reports both the expected
and actual list rather than just `false`.

`BigDecimal.equals` compares **value and scale**. `new BigDecimal("15.00")` and
`new BigDecimal("15.0")` are numerically identical and `equals` returns `false`. AssertJ's
`isEqualTo` delegates to `equals`, so it inherits that behaviour and will fail on a
difference that does not matter. `isEqualByComparingTo` delegates to `compareTo`, which
compares numeric value only:

```java
assertThat(capturedRun().getTotalRequestedAmount()).isEqualByComparingTo("15.00");
```

Where the scale genuinely is the requirement, it is asserted separately and explicitly:

```java
assertThat(amount).isEqualByComparingTo("15.75");
assertThat(amount.scale()).isEqualTo(2);
```

Two assertions instead of one `isEqualTo` — but they state two different requirements, and
a failure tells you which one broke.

### `@DisplayName` as specification

Every test carries a lowercase sentence describing observable behaviour, not the method
name:

```java
@DisplayName("zero capacity still admits weightless items")
@DisplayName("an amount finer than a minor unit is refused before any solving happens")
@DisplayName("the audit listing reports runs newest first")
```

Read as a list, these form a specification of the system. The convention is to describe what
is true of the system, not what the code calls it — `"the audit listing reports runs newest
first"` survives a rename of `findAllByOrderByCreatedAtDescIdDesc`, and it is intelligible
to someone who has not read the class.

### Mockito: `@Mock`, `when`/`thenReturn`, `thenAnswer`, `verify`, `never`

A **mock** is a generated stand-in that implements an interface with do-nothing methods
returning type defaults — `null`, `0`, `false`. `@Mock` declares one;
`@ExtendWith(MockitoExtension.class)` is what creates it before each test.

```java
@ExtendWith(MockitoExtension.class)
class SubscriptionOptimizationServiceTest {

    @Mock
    private KnapsackSolver solver;
```

`when(...).thenReturn(...)` programs a canned answer:

```java
when(solver.solve(anyList(), anyLong())).thenReturn(solution);
```

`anyList()` and `anyLong()` are **argument matchers** — this stub applies to any call. Once
one argument uses a matcher, all of them must.

`when(...).thenAnswer(...)` computes the answer from the actual call instead of returning a
fixed object:

```java
when(runRepository.save(any(OptimizationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
```

**Why `save` must return its argument.** A real `JpaRepository.save` returns the persisted
entity, and the service uses that return value:

```java
return transactionTemplate.execute(status -> toResultResponse(runRepository.save(run)));
```

An unstubbed mock returns `null`, so `toResultResponse(null)` throws a
`NullPointerException` inside the service — a failure that looks like a service bug but is
actually a test-setup bug. `thenAnswer(invocation -> invocation.getArgument(0))` makes the
mock behave like the real thing for the purpose at hand: whatever you hand it comes back.

`verify` asserts a call happened; `never()` asserts one did not:

```java
verify(solver, never()).solve(anyList(), anyLong());
verifyNoInteractions(runRepository);
```

`verify` is about *interactions*, `assertThat` is about *values*. Some requirements —
"validation runs before the solver is called", "the listing never loads subscriptions" — are
statements about interactions and cannot be expressed any other way.

**Strict stubs.** `MockitoExtension` defaults to `STRICT_STUBS`: a stub that is declared but
never used fails the test with `UnnecessaryStubbingException`. This is a feature — it stops
stale stubs accumulating — but it dictates the structure of this file. `stubSolver(...)` is
called by the thirteen tests that need it rather than from `@BeforeEach`, because
`refusesExcessPrecisionBeforeSolving` never reaches the solver and would fail on unused
stubs — and the same applies to the transaction-manager stub it now carries, since that test
never opens a transaction either.

### `ArgumentCaptor`

A captor records the arguments a mock was actually called with, so they can be asserted
afterwards. The alternative — matching inside `verify` — tells you only pass or fail:

```java
ArgumentCaptor<List<KnapsackItem>> items = ArgumentCaptor.captor();
verify(solver).solve(items.capture(), anyLong());
return items.getValue();
```

This is the central technique of `SubscriptionOptimizationServiceTest`. The service returns
a response DTO containing only accepted subscriptions, so the *rejected* ones, the
`inputIndex` values, the counts, the algorithm name, and the timestamp are invisible from
the return value. Capturing the `OptimizationRun` handed to `save(...)` is the only way to
assert on them without a database.

### The injected `Clock`

`SubscriptionOptimizationService` takes a `Clock` and calls `Instant.now(clock)`. The test
supplies a frozen one:

```java
Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)
```

which makes an exact assertion possible:

```java
assertThat(capturedRun().getCreatedAt()).isEqualTo(FIXED_NOW);
```

Had the service called `Instant.now()` directly, the timestamp would be whatever the wall
clock said, and the only available assertions would be vague — "close to now", "between
before and after". Those tolerate real bugs, such as a timestamp taken at the wrong point or
a timezone applied twice. Injecting the clock turns an untestable dependency on ambient
state into an ordinary constructor parameter.

Production supplies a truncated clock for an unrelated reason:

```java
// Truncated to milliseconds so the timestamp returned by a create matches the
// value read back from PostgreSQL, whose TIMESTAMPTZ resolution is coarser than
// the JVM clock's.
return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
```

### Testcontainers and `@ServiceConnection`

Testcontainers starts a real Docker container for the test and stops it afterwards. The
container here is PostgreSQL:

```java
@Bean
@ServiceConnection
PostgreSQLContainer postgresContainer() {
	return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
}
```

`@ServiceConnection` is the Spring Boot integration that removes the wiring. The container
picks a random host port, so its JDBC URL is not known until it has started; without this
annotation you would write a `@DynamicPropertySource` block to copy the URL, username and
password into the environment. `@ServiceConnection` derives a `JdbcConnectionDetails` bean
from the container type and Spring Boot's auto-configuration uses it in place of the
`spring.datasource.*` properties in `application.yml`. The container also outlives
individual tests: Spring caches the context, so one container serves both integration
classes.

In this project the container class comes from `org.testcontainers.postgresql` and takes no
generic parameter — Testcontainers 2.x moved the container classes into per-technology
packages and dropped the self-referential generic that 1.x used.

### `@SpringBootTest` with `RANDOM_PORT`, and `@AutoConfigureRestTestClient`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
```

`@SpringBootTest` builds the full application context. `RANDOM_PORT` starts the real
embedded servlet container on a free port, so requests travel over real HTTP through the
real filter chain and the real message converters — as opposed to `MOCK`, which simulates
the servlet layer in-process. The random port avoids collisions when tests run in parallel or
alongside a locally running instance.

In Spring Boot 4, HTTP test clients are no longer auto-configured by `@SpringBootTest`
alone. `@AutoConfigureRestTestClient`, from
`org.springframework.boot.resttestclient.autoconfigure`, is what makes a `RestTestClient`
injectable and binds it to the port the server actually chose.

`@Import` adds the container configuration to this context only.

### The `RestTestClient` fluent chain

`RestTestClient` (`org.springframework.test.web.servlet.client.RestTestClient`) builds a
request, executes it, and asserts on the response in one expression:

```java
client.post().uri(OPTIMIZE_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus().isCreated()
        .expectBody(OptimizationResultResponse.class)
        .returnResult()
        .getResponseBody();
```

`exchange()` is the pivot: everything before it describes the request, everything after
inspects the response. `expectStatus()` and `expectHeader()` assert and return the spec so
the chain continues. `expectBody(Class)` deserialises using the application's own message
converters, which means the assertions are made against the same JSON handling production
uses. `returnResult().getResponseBody()` drops out of the chain into an ordinary object,
which is what lets AssertJ take over for anything the fluent API cannot express.

Generic bodies need a `ParameterizedTypeReference` because erasure would otherwise lose the
type argument — hence the `PROBLEM` and `LISTING` constants.

---

## 4. The randomised cross-checks

There are now **three** randomised property tests, one per solver, and between them they do
more to establish correctness than all the hand-written cases combined.

| Test | File | Trials | Oracle | Property |
|---|---|---|---|---|
| `matchesBruteForceAcrossRandomisedProblems` | `DynamicProgrammingKnapsackSolverTest` | 500 | exhaustive enumeration | the DP optimum *is* the true optimum |
| `agreesWithDynamicProgramming` / `selectsSameSubsetAsDynamicProgramming` | `BranchAndBoundKnapsackSolverTest` | 1,000 each | the DP solver | branch and bound finds the same optimum *and* breaks ties identically |
| `matchesDynamicProgrammingAcrossRandomisedProblems` | `AdaptiveKnapsackSolverTest` | 500 | the DP solver | routing never changes the answer, on either branch |

The three are layered, and the order matters. The first establishes that dynamic programming
is correct against a definitional oracle. Once that holds, DP itself becomes a usable oracle
for the second, which is what makes the branch-and-bound comparison meaningful rather than
circular — comparing two unverified implementations proves only that they are wrong in the
same way. The third then checks the layer above both: not whether either algorithm is
correct, which is already settled, but whether *choosing between them* preserves the answer.

§4.1 covers the first in detail; §4.2 and §4.3 cover the other two, and §4.4 records
what they caught.

### 4.1 Dynamic programming against brute force

This single test does more to establish the solver's correctness than all the hand-written
cases combined.

```java
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
```

#### How the trials are generated

`RANDOM_TRIALS = 500` problems are drawn from a `Random` seeded with
`RANDOM_SEED = 20_250_821L`.

| Quantity | Expression | Range |
|---|---|---|
| item count | `random.nextInt(12) + 1` | 1 to 12 inclusive |
| capacity | `random.nextInt(40)` | 0 to 39 inclusive |
| weight | `random.nextInt(20)` | 0 to 19 inclusive |
| value | `random.nextInt(100)` | 0 to 99 inclusive |

Every range includes its degenerate case. Capacity can be 0, weight can be 0, value can be
0 — so weightless items, worthless items, and zero-capacity problems all occur naturally
across 500 trials rather than only in the hand-written cases. That is not a footnote: the
one real defect these cross-checks have found so far lived in exactly such an item (§4.4).

The ranges are chosen so brute force stays affordable. Twelve items is 4096 subsets, and the
DP table is at most 13 × 40 = 520 cells, so the whole 500-trial loop runs in a fraction of a
second — the entire class, all 25 tests, completes in under a tenth of a second.

#### How brute force works

```java
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
```

Each integer from `0` to `2^n - 1` is read as a subset: bit *p* set means item at position
*p* is included. `1 << items.size()` is the number of subsets; `mask & (1 << position)`
tests membership. The inner loop sums the weight and value of the chosen items, and any
subset within capacity updates the running maximum. Starting `best` at `0` handles the empty
subset implicitly, which is correct because no item has negative value.

This is a definition, not an algorithm. It is obviously right in a way the dynamic
programming table is not — there is no recurrence to get wrong, no reconstruction step, no
tie-breaking. It is exponential and useless in production, which is exactly why it is
trustworthy as an oracle.

#### Why agreement between two implementations is strong evidence

A hand-written test case encodes one expected answer, which the author computed themselves —
so it demonstrates the implementation agrees with its author on one input. If the author
misunderstood the problem, the test agrees with the misunderstanding.

Here, two independent implementations that share no code and no strategy are compared on 500
inputs the author never looked at. The DP solver builds a table of overlapping subproblems
and reconstructs a path through it; brute force enumerates subsets. For them to agree 500
times while both being wrong, they would have to be wrong in exactly the same way at every
input — implausible for implementations with nothing structurally in common.

This is property-based testing done by hand: the property is "the DP solver's optimum equals
the true optimum", checked against a definitional oracle rather than a fixed expectation.

#### Why only the total value is asserted

The Javadoc states it plainly: *"the optimal selection is not uniquely determined, so only
the optimum itself is safe to compare against."*

Many different subsets can achieve the same optimal value. With items `(5, 100)` and
`(5, 100)` and capacity 5, both `{0}` and `{1}` are optimal. Brute force as written does not
even track which subset produced its maximum, and if it did, its choice would be an artefact
of iteration order rather than a specification. Asserting the *selection* would pin an
arbitrary implementation detail and make the test fail on legitimate refactors.

The value, by contrast, is uniquely determined — it is the maximum of a finite set. That is
the property genuinely worth asserting.

The second assertion, `totalWeight <= capacity`, covers feasibility: a solution reporting the
right value while exceeding the capacity would be wrong, and value alone would not catch it.

The tie-breaking rules that *do* constrain the selection are pinned separately by the three
deterministic tie-break tests in §2.3, where the expected subset is well-defined.

#### Why the seed is fixed

`new Random(20_250_821L)` produces the same 500 problems on every run, on every machine,
forever. This matters for three reasons.

**Reproducibility.** A failure can be reproduced exactly by running the test again. With an
unseeded `Random`, a failure might occur once in CI and never again, leaving nothing to
debug.

**Bisectability.** The suite is deterministic, so `git bisect` and "did my change break
this?" both work. A test that fails intermittently for reasons unrelated to the change under
test destroys the signal.

**No flakiness.** An unseeded random test is a test that fails on some future Tuesday for an
input nobody has seen. Fixing the seed converts randomness from a runtime hazard into a
one-time generator of a large, varied, permanent fixture set. The randomness buys breadth of
coverage; the seed keeps it honest.

The cost is that the 500 problems, though varied, are always the *same* 500. This is not a
fuzzer and will not discover new inputs over time. That is a deliberate trade: broad
deterministic coverage over narrow nondeterministic coverage. If wider exploration were
wanted, the right move is to change the seed deliberately and commit the new value, not to
remove it.

The `.as(...)` descriptions exist for the same reason:

```java
.as("value on trial %d, capacity %d, items %s", trial, capacity, items)
```

When trial 384 of 500 fails, the message names the trial number, the capacity, and the full
item list, so the failing case can be lifted straight into a new permanent regression test.
§4.4 records what happened when one did.

### 4.2 Branch and bound against dynamic programming

Once §4.1 has established that dynamic programming returns the true optimum, DP itself
becomes an oracle — and a far cheaper one than enumeration, which is what makes 1,000 trials
affordable in a test class that finishes in 67 ms.

```java
private final KnapsackSolver solver = new BranchAndBoundKnapsackSolver();
private final KnapsackSolver referenceSolver = new DynamicProgrammingKnapsackSolver();
```

| Quantity | Expression | Range |
|---|---|---|
| item count | `1 + random.nextInt(14)` | 1 to 14 inclusive |
| capacity | `random.nextInt(60)` | 0 to 59 inclusive |
| weight | `random.nextInt(25)` | 0 to 24 inclusive |
| value | `random.nextInt(100)` | 0 to 99 inclusive |

The seed is `20260824L`, and the reasoning of §4.1 on fixed seeds applies unchanged.

**Two tests, not one.** Both regenerate the identical 1,000 problems from the same seed, and
they assert different things about them:

- `agreesWithDynamicProgramming` asserts `totalValue` and `totalWeight` match the reference,
  that the weight is within capacity, and — via two small helpers — that the reported totals
  are the actual sums of the selected items:

```java
assertThat(sumWeights(items, actual)).isEqualTo(actual.totalWeight());
assertThat(sumValues(items, actual)).isEqualTo(actual.totalValue());
```

  Those last two close a gap that §4.1 leaves open: a solver could report the right optimum
  alongside an index list that has nothing to do with it, and value-only assertions would
  never notice.

- `selectsSameSubsetAsDynamicProgramming` asserts the far stronger claim that
  `selectedIndices` is *equal*, element for element.

That second assertion would be illegitimate for most optimisation problems — §4.1 explains
at length why brute force can only be asked about the optimum, not the selection. It is
legitimate here for one reason: the `KnapsackSolver` interface does not merely promise *an*
optimal subset, it specifies a total order over the optimal ones — greatest value, then
least weight, then preferring to exclude items appearing later in the input. That promise
makes the selection uniquely determined, and therefore a legal thing to compare. If the
interface ever relaxed the promise, this test would have to go with it.

### 4.3 The adaptive solver against dynamic programming

The adaptive solver contains no search, so this test is not asking whether an algorithm is
correct. It asks whether the *choice* between two known-correct algorithms is transparent —
whether a caller could tell, from the answer alone, which one ran. The answer must be no.

```java
KnapsackSolver adaptiveSolver = new AdaptiveKnapsackSolver(300);
KnapsackSolver referenceSolver = new DynamicProgrammingKnapsackSolver();
```

The ceiling of 300 cells is the load-bearing part of the fixture. With 1 to 14 items the row
count runs from 2 to 15, so the ceiling `300 / rows - 1` runs from 149 down to 19, while
capacities are drawn from 0 to 59. Some trials therefore fall inside the table and some
outside, and the routing genuinely varies across the 500 problems. The reference solver is
unbounded, so it runs dynamic programming on every trial regardless.

Each trial asserts all three components — selected indices, total value, total weight —
because the promise under test is that the two branches are indistinguishable, and the
indices are where an unfaithful tie-break would surface first.

**Why the extra assertion is not optional.**

```java
if (actual.algorithmName().equals("DYNAMIC_PROGRAMMING")) {
    dynamicProgrammingTrials++;
} else {
    branchAndBoundTrials++;
}
...
// Without this the cross-check could silently degrade into comparing one branch
// against itself while the other went entirely unexercised.
assertThat(dynamicProgrammingTrials).isPositive();
assertThat(branchAndBoundTrials).isPositive();
```

Consider the failure this guards against. Suppose the fit check breaks so that *every*
problem routes to dynamic programming — a comparison inverted, a ceiling read from the wrong
field, a `+ 1` that became a `- 1`. The adaptive solver would then be compared against the
DP solver on 500 trials in which the adaptive solver *is* the DP solver. Every assertion
would pass, on every trial, for as long as the bug lived. The test would be a tautology and
would look exactly like a healthy one: green, fast, five hundred trials.

That is the specific hazard of testing a component whose only job is delegation. The
comparison assertions verify the *answers*; only the counters verify that the test exercised
anything. Two lines convert a test that cannot fail into one that fails the moment routing
collapses to a single branch — and they are worth more than the 500 comparisons they
accompany, because the comparisons only mean anything if these hold.

The counters deliberately assert `isPositive()` rather than a specific split. Pinning "217
dynamic programming trials and 283 branch-and-bound trials" would encode an artefact of the
seed and would fail on any harmless change to the ranges. What matters is that neither
branch is dead.

### 4.4 What the cross-check actually caught

**The failure.** When `selectsSameSubsetAsDynamicProgramming` was first run against branch
and bound, it failed on exactly one trial in a thousand. The two solvers agreed on the
optimum and agreed on the total weight, and disagreed about a single index: the problem
contained an item of **weight 0 and value 0**, which either solver could include or exclude
with byte-identical totals.

Both answers were optimal. Only the interface's tie-break rule distinguished them — and that
rule is not decorative, because `SubscriptionOptimizationService` maps those indices onto
investors and writes an accepted-or-declined flag for each. A worthless, weightless candidate
recorded as accepted by one solver and declined by the other is a visible difference in the
audit trail, produced by requests the caller would consider identical.

**The fix, and the fix that was not made.** The tempting response is to weaken the assertion:
compare only the totals, or filter zero-value items out of the generated problems. Either
would have made the suite green in under a minute, and either would have destroyed the only
evidence that the two solvers disagreed about anything.

What was done instead was to implement the full tie-break rule in branch and bound. That
meant a comparison over complete selections:

```java
/**
 * Whether {@code candidate} is preferred over {@code incumbent} under the
 * interface's final tie-break rule, which favours excluding items appearing
 * later in the input.
 * ...
 * This reproduces the preference that falls out of the dynamic programming
 * solver's cell-by-cell iteration, so both solvers name the same subset when
 * several are equally optimal.
 */
private static boolean excludesLaterItems(List<Integer> candidate, List<Integer> incumbent)
```

and, inseparably from it, a pruning comparison that sits one character away from the
textbook version:

```java
// Deliberately strict. Textbook branch and bound prunes on <=, since a
// subtree that can only match the incumbent value adds nothing. Here a tie
// on value is broken further by weight and then by item index, so a tying
// subtree may still hold the preferred solution and must be explored.
if (upperBound(depth, value, weight) < bestValue) {
    return;
}
```

`<` rather than `<=`. Textbook branch and bound prunes any subtree that cannot *beat* the
incumbent, on the reasoning that a subtree which merely ties adds nothing — true when only
the optimum matters, false the moment ties are broken by a further rule. Prune on `<=` and
the subtree holding the preferred tied selection is discarded before it is ever examined:
no crash, no wrong optimum, no exception, just a different equally optimal subset. That is
about as close to invisible as a defect gets.

**Why this is the strongest argument in this document for property-based testing.** Every
hand-written test in §2.4 passed throughout, and had to: each encodes a case its author
thought of, and nobody sits down to write "what if a candidate applies for nothing, pays no
fee, and the tie is otherwise perfect". The defect lived in an input nobody would have
chosen. It was found because a thousand inputs were generated that nobody chose.

Three properties of the test turned the discovery into a short fix rather than a long,
intermittent mystery:

1. **The seed is fixed.** The failing trial reproduced on every run, on every machine, in the
   same position. An unseeded generator would have produced a failure that appeared once in
   CI, vanished on re-run, and got filed as flaky — with the disagreement still shipping.
2. **The AssertJ description carries the fixture.** The message named the trial number, the
   capacity, and the full item list, so the offending problem could be lifted off the console
   and pasted straight into a scratch test. Without `.as(...)` the message would have read
   `expected: [0, 2] but was: [0, 1, 2]` — accurate, useless, and an afternoon of adding
   print statements inside a thousand-iteration loop.
3. **The oracle was already trusted.** Because §4.1 had established the DP solver against
   exhaustive enumeration, there was never an argument about *which* solver was wrong.

The general form: a hand-written test asserts that the code does what its author expected on
inputs its author imagined. A property test asserts that an invariant holds across inputs
nobody imagined — and when it fails, a fixed seed and a description carrying the fixture are
what convert "something, somewhere, is wrong" into a specific, reproducible, one-character
defect.

---

## 5. Design decisions worth defending

### 5.1 Testcontainers rather than H2

**Decision.** The integration tests run against a real PostgreSQL in Docker, not an
in-memory database.

**Reasoning.** The schema is PostgreSQL-specific and is applied by Flyway, not generated by
Hibernate. It uses `NUMERIC(19, 2)`, `TIMESTAMPTZ`, `BIGINT GENERATED BY DEFAULT AS
IDENTITY`, named `CHECK` constraints, and `ON DELETE CASCADE`. With `ddl-auto: validate`,
the application refuses to start if the JPA mappings do not match the migrated schema — so
`contextLoads` is only meaningful against the database the migrations were written for.

H2 would either reject that DDL or accept it in a compatibility mode that behaves
differently, and the difference lands precisely where this application is sensitive:
`NUMERIC(19, 2)` scale handling and `TIMESTAMPTZ` resolution. The
`readsBackExactlyWhatTheCreateReturned` test asserts byte-for-byte agreement between an
in-memory response and one read back from the database — an assertion that is only worth
anything against the real engine.

**Cost.** Docker must be running, and the suite takes ≈ 26 s instead of ≈ 3 s. That cost is
confined to one tier, which is the point of the split described in §1.

### 5.2 No `@Transactional` rollback in the integration test

**Decision.** The integration test does not use Spring's transactional rollback. It clears
state explicitly instead:

```java
// The server serves requests on its own threads, so a test-managed transaction would
// not cover them; state is cleared outright instead of rolled back.
@BeforeEach
void clearRuns() {
    runRepository.deleteAll();
}
```

**Reasoning.** `@Transactional` on a test method starts a transaction on the *test* thread
and rolls it back afterwards. That works when the test calls the service directly. It does
not work here: with `RANDOM_PORT`, requests go over real HTTP and the server handles them on
its own worker threads, in their own transactions, which commit independently of anything
the test thread is doing. The test's transaction would roll back nothing that the requests
wrote, while creating two confusing side effects — data written by requests would be
invisible to the test's own repository reads, and rows would survive the test anyway.

`deleteAll()` in `@BeforeEach` is honest about what is happening: the data is really
committed, and it is really deleted before the next test. Running it *before* each test
rather than after also means a failed test leaves its data behind for inspection.

### 5.3 Ordering and pagination tests write rows directly

**Decision.** `listsRunsNewestFirst` and `slicesResultsIntoPages` create their rows through
the repository, not the API:

```java
storeRun(TEN_O_CLOCK, new BigDecimal("10"));
storeRun(ELEVEN_O_CLOCK, new BigDecimal("20"));
storeRun(TWELVE_O_CLOCK, new BigDecimal("30"));
```

**Reasoning**, recorded on the helper itself:

```java
/**
 * Stores a run directly, bypassing the create endpoint.
 *
 * <p>The listing tests are about ordering and slicing, and the API cannot express the
 * timestamp a run is created with: it comes from a clock truncated to milliseconds,
 * and the {@code created_at DESC, id DESC} tie-break falls to a random UUID, so three
 * runs created in quick succession order arbitrarily. Writing the timestamps here
 * makes the expected order a fact of the fixture rather than of how fast the machine
 * happens to be. The create path itself is exercised through the API by the cases
 * above.
 * ...
 */
```

The query is `findAllByOrderByCreatedAtDescIdDesc`. Production assigns `createdAt` from a
clock truncated to 1 ms and `id` from `UUID.randomUUID()`. Three POSTs issued back to back
can therefore land in the same millisecond, at which point ordering falls to a comparison of
random UUIDs and the expected sequence is a coin toss. The test would pass on a slow machine
and fail on a fast one — the worst kind of failure, because it looks like a real bug.

Writing timestamps an hour apart makes the ordering a property of the fixture. What is under
test is the query and the pagination envelope, and both are exercised fully; the create path
has seven dedicated tests of its own.

**Trade-off.** These two runs have zero counts and no `subscription_request` children, which
is a state the API would never produce. That is acceptable here because the listing reads
only the run's stored columns and never touches the association — the very property §5.4
pins. It would not be acceptable for a test that reads subscriptions, and none of these do.

### 5.4 A Mockito spy proves the N+1 fix

**Decision.** The `findAll` test wraps its fixture in a spy and verifies a method was never
called:

```java
OptimizationRun run = spy(storedRun(requestId));
...
verify(run, never()).getSubscriptions();
```

**Reasoning.** A **spy** wraps a real object: calls run the real implementation, but Mockito
records them so `verify` works. That is what is needed here, because the summary must be
built from the run's real field values *and* the association must be provably untouched.

The requirement is a performance property. `OptimizationRunSummary` reports
`candidateCount` and `acceptedCount`, which are denormalised onto `optimization_run` by
migration V3 for exactly this reason:

```sql
-- The counts are known when a run is created and a run is never updated afterwards, so
-- storing them here lets the audit listing report "n of m accepted" without touching
-- subscription_request at all.
```

The obvious implementation — counting `run.getSubscriptions()` — would be functionally
correct and would pass every value-based assertion, while issuing one extra SELECT per run
on the page. That is the N+1 problem, and it is invisible to assertions about values. A page
of 100 runs would silently become 101 queries.

`verify(run, never()).getSubscriptions()` states the requirement directly: *building a
summary must not touch the association*. It is the only form of assertion that can express
it, and it fails the moment someone reintroduces the regression.

### 5.5 POST-then-GET equality is the currency and clock regression test

**Decision.**

```java
OptimizationResultResponse created = optimize(assignmentExample());

OptimizationResultResponse reloaded = client.get().uri(RUN_PATH, created.requestId())
        ...
        .getResponseBody();

assertThat(reloaded).isEqualTo(created);
assertThat(reloaded.createdAt()).isEqualTo(created.createdAt());
```

**Reasoning.** The two responses are built by different routes. The POST response is mapped
from an entity still in memory, holding the `BigDecimal` values the constructor normalised
and the `Instant` the clock produced. The GET response is mapped from an entity Hibernate
rehydrated from PostgreSQL, holding values that made a round trip through `NUMERIC(19, 2)`
and `TIMESTAMPTZ`. Asserting whole-record equality demands that both routes agree exactly.

`OptimizationResultResponse` is a record, so `equals` is component-wise, and its
`BigDecimal` components are compared with `BigDecimal.equals` — **scale included**. That
strictness is deliberate here, and it is why the assertion catches two specific bugs:

*Currency scale.* A caller may send `5`, `5.0`, or `5.00`. Without normalisation the
in-memory response would render the caller's scale while the database would always return
scale 2, and the records would differ. `CurrencyScale.normalize` is what makes them agree,
using `RoundingMode.UNNECESSARY` so a value that would need rounding fails loudly instead of
silently losing a fraction.

*Clock truncation.* The JVM clock offers finer-than-microsecond resolution;
PostgreSQL `TIMESTAMPTZ` stores microseconds. An untruncated timestamp would be silently
rounded on the way into the database, and the value read back would differ from the one
returned by the POST. `Clock.tick(Clock.systemUTC(), Duration.ofMillis(1))` in
`AlgorithmConfiguration` is what prevents that, and this test is what would catch its
removal.

The explicit `createdAt` assertion on the following line is redundant — record equality
already covers it. It is kept because it names the second of the two regressions, which a
whole-record comparison does not.

### 5.6 The two randomised solver tests are split, not merged

**Decision.** `BranchAndBoundKnapsackSolverTest` runs the same 1,000 generated problems
twice, in two separate tests: one asserting the totals and their self-consistency, the other
asserting the selected indices.

**Reasoning.** Merging them would halve the work and lose the diagnosis. The two assertions
fail for entirely different reasons, and the split is what makes the failure message answer
the first question a reader will ask.

If `agreesWithDynamicProgramming` goes red, branch and bound found a **worse optimum** —
the search is wrong. The bound is unsound, or the capacity check is off, or a subtree that
held the answer was pruned on value. That is a correctness bug and the solver is unusable.

If `selectsSameSubsetAsDynamicProgramming` goes red *while the first stays green*, both
solvers found the optimum and they disagree only about **which equally optimal subset to
name**. That is a tie-break bug: real, worth fixing (§4.4 is exactly this failure), and of a
completely different severity — no caller gets a suboptimal allocation, they get a different
but equally good one.

One merged test would report "branch and bound disagrees with dynamic programming" for both,
and the reader would have to work out which kind of disagreement it was before they could
judge how alarming it is. Two tests answer that from the test name alone. The duplicated
generation loop costs about 30 ms, which is a good price for a diagnosis.

### 5.7 The algorithm-name regression test stubs an obviously fake value

**Decision.** The test pinning where the recorded algorithm name comes from stubs a name no
real solver could ever return:

```java
stubSolver(new KnapsackSolution("NOT_A_REAL_ALGORITHM", List.of(0, 1), 1500L, 32000L));

service.optimize(assignmentExample());

assertThat(capturedRun().getAlgorithmUsed()).isEqualTo("NOT_A_REAL_ALGORITHM");
```

**Reasoning.** The bug this test exists to catch is the service hardcoding a name instead of
reading one — writing `"DYNAMIC_PROGRAMMING"` into the run because that is what the solver
used to be. A stub named `"DYNAMIC_PROGRAMMING"` could not catch that: the assertion would
pass against the bug, because the hardcoded constant and the stubbed value would coincide.
The stub must be a value production code has no way to produce.

Fakeness is doing a second job, though, and it is the reason the value is
`"NOT_A_REAL_ALGORITHM"` rather than something merely arbitrary like `"XYZZY"`. A reader
meeting this test cold has to reconstruct why it is here. A name that could not possibly be
real announces the intent in the fixture itself: nobody writes that string unless the point
is *where the value came from* rather than what it is. The test is self-documenting in a way
that a plausible-looking stub would not be, and `@DisplayName` says the rest — "the recorded
algorithm name comes from the returned solution, not a constant".

`recordsTheAlgorithmNameOfEachRunSeparately` sits next to it with a plausible name,
`"BRANCH_AND_BOUND"`, and covers the complementary case: not just that the value is read,
but that it is read *per run*, which is what an adaptive solver requires and what a field
cached in the constructor would break.

This is the same reasoning as the older `"TEST_SOLVER"` stub the test replaced, applied to
a value that now travels on the solution rather than on the solver — see §6, where exactly
this bug was introduced deliberately and this test was the thing that caught it.

### 5.8 The service test stubs a `PlatformTransactionManager`

**Decision.** `SubscriptionOptimizationServiceTest` has a fourth mock, and `stubSolver`
gives it a real status object to hand back:

```java
@Mock
private PlatformTransactionManager transactionManager;
...
when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
```

**Reasoning.** This is not test scaffolding for its own sake; it is the visible consequence
of a production decision. `optimize` is deliberately **not** annotated `@Transactional`.
Scaling the amounts and running the knapsack search touch no database at all, and a
method-level annotation would check a pooled connection out on entry and hold it for the
whole call — including a search that can run for a noticeable time on a large problem. A
handful of concurrent optimisations would exhaust the pool while doing no database work.

So the transaction covers only the write, opened programmatically:

```java
return transactionTemplate.execute(status -> toResultResponse(runRepository.save(run)));
```

`TransactionTemplate` rather than an annotation on a private method, because a private
method called from `optimize` goes through `this` rather than through the Spring proxy — the
annotation would be silently ignored and no transaction would start at all. That is a trap
worth naming, because the broken version looks correct and passes every unit test.

The cost is that the service now takes a `PlatformTransactionManager`, so the unit test must
supply one. A bare mock would return `null` from `getTransaction`, which the current callback
tolerates only because it never consults the status; stubbing a `SimpleTransactionStatus`
costs one line and removes a null that would become a `NullPointerException` the moment the
callback grew. The stub lives in `stubSolver` rather than `@BeforeEach` for the same reason
the other stubs do: `MockitoExtension` runs in strict-stubs mode and would fail the tests
that never reach the write.

The boundary itself — that the mapping stays *inside* the transaction, so the run's
subscriptions association is read before the persistence context closes — is not provable
with mocks. It is proved by the POST-then-GET integration test in §5.5, which would fail with
a `LazyInitializationException` if the transaction closed too early.

### 5.9 Known gaps

Recorded honestly rather than argued away.

- **The brute-force cross-check does not check selection consistency.** It asserts the
  total value and that the weight is within capacity, but never that `selectedIndices`
  actually sum to the reported `totalWeight` and `totalValue`. A solver returning the correct
  optimum alongside an unrelated index list would pass that test. The gap is narrower than it
  was — the branch-and-bound cross-check asserts exactly that consistency (§4.2), and the
  adaptive one compares indices directly (§4.3) — but it remains open for the dynamic
  programming solver's own randomised test, where the deterministic cases carry it instead.
- **Nothing tests the adaptive solver under a ceiling large enough to matter.** The routing
  tests use ceilings of 10, 30, and 300 cells so the boundary is reachable by hand. The
  configured production ceiling is 10,000,000, and no test allocates a table anywhere near
  it; a defect that only appears at realistic memory pressure would not be caught here.
- **The duplicated fit check is tested on both sides but not pinned together.** §2.5 tests
  the adaptive solver's condition and §2.3 tests the dynamic programming solver's, at the
  same boundary values. Nothing fails if a future change updates one and not the other —
  the two conditions are compared only by a reader.
- **The branch-and-bound solver's search time is bounded but not measured.** The node limit
  (§2.4) proves the search *stops*, and the flat-fee fixture proves it stops on the shape
  that defeats pruning. Nothing asserts how long a refusal takes, or how close a realistic
  1,000-candidate request comes to the ceiling; both are still judged by hand.
- **`refusesIdentifierThatIsNotAUuid` never inspects the body.** It asserts status and
  content type and discards the result, so it would still pass if the problem document's
  `title` or `detail` regressed. The other rejection tests check the body.
- **`rendersAmountsAtTwoDecimalPlaces` asserts on a deserialised value, not raw JSON.** The
  reasoning in §2.9 holds, but it is one inference removed from the wire format; a raw-body
  assertion would be more direct.
- **`capsOversizedPageRequests` mostly tests framework configuration.** The cap comes from
  `spring.data.web.pageable.max-page-size: 100`, not from application code. It is still worth
  having as a guard against the setting being dropped.
- **Time in the `web` and `service` tiers is pinned to the same instants** used elsewhere in
  the suite, but nothing tests behaviour across a daylight-saving or timezone boundary. The
  application stores UTC throughout, so this is likely fine, and it is untested.

---

## 6. Validating the suite

A test suite that passes proves nothing on its own — a suite of empty test bodies also
passes. To check that these tests actually detect the bugs they claim to, three deliberate
bugs were introduced into a throwaway copy of the project, one at a time, and the suite was
run against each. The project's own sources were not modified.

| Bug | Result | Caught by |
|---|---|---|
| `acceptedCount` and `candidateCount` swapped in `SubscriptionOptimizationService` | 12 errors in the service test, 7 failures in the integration test | see below |
| the recorded algorithm name replaced with the literal `"DYNAMIC_PROGRAMMING"` | 1 failure + 11 errors in the service test | `recordsTheSolverOwnName`, now `recordsTheAlgorithmNameFromTheSolution` |
| `movePointRight(SCALE)` changed to `movePointRight(SCALE + 1)` in `MinorUnits` | 12 failures across three files | see below |

**Swapped counts.** All 12 affected service tests reported *errors*, not failures — and none
of them reached an assertion:

```
java.lang.IllegalArgumentException: acceptedCount must not exceed candidateCount: 4 > 2
```

The `OptimizationRun` constructor rejected the invalid state outright. The intended detector,
`recordsBothCountsFromTheirOwnSource`, never got as far as comparing 2 against 4, because the
entity refused to be constructed at all. This is defence in depth working as designed: the
invariant is enforced at the domain boundary, so the bug cannot produce a bad row even if no
test asserted on the counts. The integration test reported 7 failures, each a
`500 INTERNAL_SERVER_ERROR` where a `201 CREATED` was expected. Notably, the two integration
cases where every candidate was accepted still passed — with `accepted == candidates` the
swap is undetectable — which is a reminder that fixtures need an accepted count strictly
below the candidate count to have any diagnostic power.

**Hardcoded algorithm name.** `recordsTheSolverOwnName` failed with `expected:
"TEST_SOLVER"`, exactly as designed: the stubbed name is deliberately distinctive so it
cannot coincide with a constant in production code. The other 11 service tests errored with
`UnnecessaryStubbingException`, because `when(solver.name())` in `stubSolver` became a stub
nobody called. That is Mockito's strict-stub mode reporting the same defect from a different
angle — the production code stopped asking the collaborator a question it used to ask. No
integration test caught this one, and none could: the real solver genuinely is named
`DYNAMIC_PROGRAMMING`, and `OptimizationRunSummary` does not expose the algorithm name over
the API at all.

*This experiment predates the refactor that moved the name onto the solution*, so the exact
mechanism it describes no longer exists: there is no `solver.name()` to stub out, and the
strict-stubs half of the signal went with it. The mutation it stands for does survive —
hardcoding a literal where `solution.algorithmName()` should be read — and its detector is
`recordsTheAlgorithmNameFromTheSolution`, which stubs `"NOT_A_REAL_ALGORITHM"` for precisely
the reason the old test stubbed `"TEST_SOLVER"` (§5.7). The mutation is also strictly more
dangerous now than it was then: with an adaptive solver choosing per request, a hardcoded
`"DYNAMIC_PROGRAMMING"` would be *correct on most runs* and quietly wrong on the large-capacity
ones that actually ran branch and bound, producing an audit trail that misreports how a
result was reached. The conclusion about integration coverage is unchanged and still worth
stating: nothing at the HTTP tier would notice, because the algorithm name is never exposed
over the API.

The experiment has not been repeated against the current suite. Rerunning it — and adding a
fourth mutation that inverts the adaptive solver's fit check, which §4.3 argues would be
caught only by the routing counters — is the obvious next step for this section.

**Wrong scale factor.** 7 of 12 `MinorUnitsTest` tests failed — every scaling case except
`scalesZero`, which is immune because zero scales to zero at any factor, plus the two
rejection tests and the two `toDecimal` tests, which do not use the mutated line. Two service
tests failed (`scalesAmountsToMinorUnits`, `scalesFractionalAmountsExactly`) and three
integration tests failed (`acceptsTheBestFittingCombination`, `keepsFractionalAmountsIntact`,
`rendersAmountsAtTwoDecimalPlaces`). The failure appeared at all three tiers, with the
narrowest and most precise report coming from the unit tier — which is exactly the behaviour
§1 describes.

In all three cases the algorithm tests stayed green, correctly: none of the three bugs is in
the algorithm.
