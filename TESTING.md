# Testing

This document explains what the test suite covers, how each test is written, and why it is
written that way. It is meant to be read alongside the code — every excerpt below is quoted
verbatim from a file under `src/test/java`.

---

## 1. Overview

The suite has **80 tests** in seven files, falling into three tiers.

| Tier | Files | Tests | Docker | Time |
|---|---|---|---|---|
| Pure unit | `KnapsackItemTest`, `KnapsackSolutionTest`, `DynamicProgrammingKnapsackSolverTest`, `MinorUnitsTest` | 45 | no | ≈ 0.7 s |
| Unit with mocks | `SubscriptionOptimizationServiceTest` | 16 | no | ≈ 2.4 s |
| Full-stack integration | `SubscriptionCapacityApplicationTests`, `SubscriptionApiIntegrationTest` | 19 | **yes** | ≈ 26 s |

`./mvnw clean test` runs all three tiers and takes about 42 seconds end to end. Most of the
integration cost is one-time: starting the PostgreSQL container and building the Spring
context. Both integration classes share a single container and a single cached context, so
the second class costs ≈ 7 s rather than another ≈ 19 s.

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

### 2.2 `algorithm/KnapsackSolutionTest` — 5 tests

`KnapsackSolution` is the solver's return type. Two of its five tests are about the
defensive copy in its compact constructor, which is the kind of thing that is easy to
delete during a refactor and hard to notice.

```java
@Test
@DisplayName("the index list is copied, so later changes to the caller's list are not visible")
void copiesIndexListDefensively() {
    List<Integer> mutableIndices = new ArrayList<>(List.of(0, 1));

    KnapsackSolution solution = new KnapsackSolution(mutableIndices, 15, 320);
    mutableIndices.add(2);
    mutableIndices.clear();

    assertThat(solution.selectedIndices()).containsExactly(0, 1);
}
```

Note the fixture is `new ArrayList<>(List.of(0, 1))`, not `List.of(0, 1)`. An immutable list
could not demonstrate the point — you need a list you can mutate afterwards. Mutating it
twice (`add` then `clear`) is belt and braces: if the record held a reference rather than a
copy, `selectedIndices()` would be empty and the assertion would fail loudly.

`returnsUnmodifiableIndexList` covers the other direction — that a caller cannot mutate the
list they get back — by asserting `UnsupportedOperationException` from
`solution.selectedIndices().add(2)`. Together the two tests establish that the record is
genuinely immutable in both directions, which is what `List.copyOf` buys.

The remaining three: `rejectsNegativeTotalWeight` and `rejectsNegativeTotalValue` mirror the
`KnapsackItem` guards, and `emptySolutionSelectsNothing` pins that the `empty()` factory
returns an empty selection with zero totals — the value the service relies on when nothing
fits.

### 2.3 `algorithm/DynamicProgrammingKnapsackSolverTest` — 23 tests

The largest and most important unit test file. It covers the exact 0/1 knapsack solver.

**Setup.** Two constants control the randomised cross-check (§4), and the solver under test
is a plain field — no mocks, no Spring, nothing to inject:

```java
private static final int RANDOM_TRIALS = 500;
private static final long RANDOM_SEED = 20_250_821L;

private final DynamicProgrammingKnapsackSolver solver = new DynamicProgrammingKnapsackSolver();
```

The no-arg constructor uses `DEFAULT_MAX_TABLE_CELLS = 10_000_000`. That is *not* the value
the running application uses — `application.yml` configures `max-table-cells: 50000000` — so
the two ceiling tests construct their own bounded solvers instead of relying on the default.

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

The 23rd test is `matchesBruteForceAcrossRandomisedProblems`, covered in §4.

### 2.4 `service/MinorUnitsTest` — 12 tests

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

### 2.5 `service/SubscriptionOptimizationServiceTest` — 16 tests

A plain Mockito test with no Spring context. This is the tier that checks *orchestration*:
what the service passes to the solver, what it builds to persist, and what it returns.

**Setup.**

```java
private static final Instant FIXED_NOW = Instant.parse("2026-06-01T10:00:00Z");
private static final String SOLVER_NAME = "TEST_SOLVER";

/** The assignment example: A and B together fill a capacity of 15 for a fee of 320. */
private static final KnapsackSolution ASSIGNMENT_SOLUTION =
        new KnapsackSolution(List.of(0, 1), 1500L, 32000L);

@Mock
private KnapsackSolver solver;

@Mock
private OptimizationRunRepository runRepository;

private SubscriptionOptimizationService service;

@BeforeEach
void createService() {
    service = new SubscriptionOptimizationService(
            solver, runRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
}
```

The service is constructed by hand. Nothing here needs a Spring context: the class has one
constructor and three collaborators, so `new` is faster, more explicit, and cannot break
because of an unrelated bean.

Note that `ASSIGNMENT_SOLUTION` is a *canned* answer. This file does not test whether
`(0, 1)` is the right selection — `DynamicProgrammingKnapsackSolverTest` did that. Here the
solver is a stub whose only job is to return something predictable so the service's handling
of it can be examined.

**The stubbing helper.** Three stubs are needed together, so they live in one method:

```java
private void stubSolver(KnapsackSolution solution) {
    when(solver.solve(anyList(), anyLong())).thenReturn(solution);
    // Left unstubbed, name() returns null and the OptimizationRun constructor rejects
    // it, so every run-producing case needs this regardless of what it asserts on.
    when(solver.name()).thenReturn(SOLVER_NAME);
    // A default mock returns null and the service maps the saved entity into its
    // response, so save() must hand back what it was given.
    when(runRepository.save(any(OptimizationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
}
```

Both comments record a trap. An unstubbed mock method returns the type's default — `null`
for objects — and both of those nulls cause a confusing failure a long way from their cause:
`solver.name()` returning `null` trips `Objects.requireNonNull(algorithmUsed, …)` inside the
`OptimizationRun` constructor, and `save()` returning `null` produces a
`NullPointerException` when the service maps the result. See §3 for the general technique.

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

**The `optimize` cases (13).**

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
8. `recordsTheSolverOwnName` — asserts `getAlgorithmUsed()` equals `"TEST_SOLVER"`. The
   value is distinctive on purpose: it cannot coincidentally match a hardcoded constant in
   production code. See §6.
9. `convertsTotalsBackFromMinorUnits` — `15.00` and `320.00` by `isEqualByComparingTo`, from
   the solver's `1500` and `32000`.
10. `timestampsFromTheInjectedClock` — `getCreatedAt()` equals `FIXED_NOW` exactly. Only
    possible because the clock is injected; see §3.
11. `responseReportsAcceptedSubscriptionsOnly` — the returned DTO names exactly Investor A
    and Investor B. Distinct from case 4: the *run* holds four, the *response* shows two.
12. `persistsRunWhenNothingIsSelected` — stubs `KnapsackSolution.empty()` and asserts the
    response is empty with zero totals **and** that the run is still saved with four
    subscriptions, all rejected, `acceptedCount` 0. "Nothing fits" is a successful run, not
    an error.
13. `refusesExcessPrecisionBeforeSolving` — passes `5.123` and asserts
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

### 2.6 Integration support: `TestcontainersConfiguration` and friends

Three small files in the root test package.

`TestcontainersConfiguration` declares the database container as a Spring bean:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}
}
```

`@TestConfiguration` marks it as opt-in — it is not picked up by component scanning and
applies only where it is explicitly `@Import`ed. `@ServiceConnection` is what removes the
usual boilerplate; see §3.

Two notes on this file. It is `public` so that `SubscriptionApiIntegrationTest`, which lives
in the `web` subpackage, can name it in `@Import`. And `postgres:latest` is a floating tag,
so the database version can change under the suite without any change to the repository.
Pinning a major version would make the suite reproducible over time.

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

### 2.7 `web/SubscriptionApiIntegrationTest` — 18 tests

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

**Listing and pagination (4 tests).**

- `listsRunsNewestFirst` — three runs written directly at 10:00, 11:00 and 12:00 with
  capacities 10, 20 and 30. Asserts `totalElements` 3, capacities in the order
  `"30.00", "20.00", "10.00"`, and timestamps `containsExactly(TWELVE, ELEVEN, TEN)`.
- `listingReportsBothCounts` — via the API, `acceptedCount` 2 and `candidateCount` 4.
- `capsOversizedPageRequests` — `?size=500` returns `size` 100, the
  `spring.data.web.pageable.max-page-size` configured in `application.yml`.
- `slicesResultsIntoPages` — `?page=0&size=2` over three stored runs gives 2 items,
  `totalPages` 2, `totalElements` 3.

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
return toResultResponse(runRepository.save(run));
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
called by the twelve tests that need it rather than from `@BeforeEach`, because
`refusesExcessPrecisionBeforeSolving` never reaches the solver and would fail on unused
stubs.

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
	return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
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

## 4. The randomised cross-check

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

### How the trials are generated

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
across 500 trials rather than only in the hand-written cases.

The ranges are chosen so brute force stays affordable. Twelve items is 4096 subsets, and the
DP table is at most 13 × 40 = 520 cells, so the whole 500-trial loop runs in a fraction of a
second — the entire class, all 23 tests, completes in about half a second.

### How brute force works

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

### Why agreement between two implementations is strong evidence

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

### Why only the total value is asserted

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

### Why the seed is fixed

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

### 5.6 Known gaps

Recorded honestly rather than argued away.

- **The randomised test does not check selection consistency.** It asserts the total value
  and that the weight is within capacity, but never that `selectedIndices` actually sum to
  the reported `totalWeight` and `totalValue`. A solver returning the correct optimum
  alongside an unrelated index list would pass this test. The deterministic tests do assert
  selections, so the gap is covered in aggregate, but not by this test.
- **`postgres:latest` is a floating tag.** The database version can change under the suite
  without any change to the repository.
- **`refusesIdentifierThatIsNotAUuid` never inspects the body.** It asserts status and
  content type and discards the result, so it would still pass if the problem document's
  `title` or `detail` regressed. The other rejection tests check the body.
- **`rendersAmountsAtTwoDecimalPlaces` asserts on a deserialised value, not raw JSON.** The
  reasoning in §2.7 holds, but it is one inference removed from the wire format; a raw-body
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
| `solver.name()` replaced with the literal `"DYNAMIC_PROGRAMMING"` | 1 failure + 11 errors in the service test | `recordsTheSolverOwnName` |
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
