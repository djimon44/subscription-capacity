package com.arcticblu.subscriptioncapacity.service;

import com.arcticblu.subscriptioncapacity.algorithm.KnapsackItem;
import com.arcticblu.subscriptioncapacity.algorithm.KnapsackSolution;
import com.arcticblu.subscriptioncapacity.algorithm.KnapsackSolver;
import com.arcticblu.subscriptioncapacity.config.OptimizationProperties;
import com.arcticblu.subscriptioncapacity.domain.OptimizationRun;
import com.arcticblu.subscriptioncapacity.domain.SubscriptionRequest;
import com.arcticblu.subscriptioncapacity.repository.OptimizationRunRepository;
import com.arcticblu.subscriptioncapacity.repository.SubscriptionRequestRepository;
import com.arcticblu.subscriptioncapacity.web.dto.AcceptedSubscription;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationResultResponse;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationRunSummary;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizeRequest;
import com.arcticblu.subscriptioncapacity.web.dto.PagedResponse;
import com.arcticblu.subscriptioncapacity.web.dto.SubscriptionRequestPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionOptimizationServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final String SOLUTION_ALGORITHM_NAME = "TEST_ALGORITHM";

    /** The assignment example: A and B together fill a capacity of 15 for a fee of 320. */
    private static final KnapsackSolution ASSIGNMENT_SOLUTION =
            new KnapsackSolution(SOLUTION_ALGORITHM_NAME, List.of(0, 1), 1500L, 32000L);

    private static final OptimizationProperties PROPERTIES = new OptimizationProperties(10_000_000, 100);

    @Mock
    private KnapsackSolver solver;

    @Mock
    private OptimizationRunRepository runRepository;

    @Mock
    private SubscriptionRequestRepository subscriptionRequestRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private SubscriptionOptimizationService service;

    @BeforeEach
    void createService() {
        service = new SubscriptionOptimizationService(solver, runRepository, subscriptionRequestRepository,
                PROPERTIES, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), transactionManager);
    }

    // --- optimize ------------------------------------------------------------------

    @Test
    @DisplayName("decimal amounts are scaled to whole minor units before the solver sees them")
    void scalesAmountsToMinorUnits() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedItems()).extracting(KnapsackItem::weight)
                .containsExactly(500L, 1000L, 300L, 800L);
        assertThat(capturedItems()).extracting(KnapsackItem::value)
                .containsExactly(12_000L, 20_000L, 8_000L, 16_000L);
        assertThat(capturedCapacity()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("amounts carrying hundredths scale without losing the fraction")
    void scalesFractionalAmountsExactly() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(new OptimizeRequest(
                new BigDecimal("15.75"),
                List.of(
                        new SubscriptionRequestPayload("Investor A", new BigDecimal("5.25"), new BigDecimal("120")),
                        new SubscriptionRequestPayload("Investor B", new BigDecimal("10"), new BigDecimal("200")),
                        new SubscriptionRequestPayload("Investor C", new BigDecimal("3"), new BigDecimal("80")),
                        new SubscriptionRequestPayload("Investor D", new BigDecimal("8"), new BigDecimal("160"))),
                true));

        assertThat(capturedCapacity()).isEqualTo(1575L);
        assertThat(capturedItems().getFirst().weight()).isEqualTo(525L);
    }

    @Test
    @DisplayName("each item carries the position its candidate held in the request")
    void itemIndicesMatchInputPositions() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedItems()).extracting(KnapsackItem::index).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("every candidate is persisted, not only the ones that were accepted")
    void persistsEveryCandidate() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getSubscriptions()).hasSize(4);
    }

    @Test
    @DisplayName("only the candidates the solver selected are marked accepted")
    void marksOnlySelectedCandidatesAccepted() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getSubscriptions())
                .extracting(SubscriptionRequest::isAccepted)
                .containsExactly(true, true, false, false);
    }

    @Test
    @DisplayName("the persisted subscriptions keep the order the candidates arrived in")
    void preservesInputOrder() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getSubscriptions())
                .extracting(SubscriptionRequest::getInputIndex)
                .containsExactly(0, 1, 2, 3);
        assertThat(capturedRun().getSubscriptions())
                .extracting(SubscriptionRequest::getInvestorName)
                .containsExactly("Investor A", "Investor B", "Investor C", "Investor D");
    }

    @Test
    @DisplayName("the accepted count counts the selection while the candidate count counts the submission")
    void recordsBothCountsFromTheirOwnSource() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getAcceptedCount()).isEqualTo(2);
        assertThat(capturedRun().getCandidateCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("the recorded algorithm name comes from the returned solution, not a constant")
    void recordsTheAlgorithmNameFromTheSolution() {
        // A solver may pick a different algorithm per request, so the only trustworthy
        // source is the solution it hands back. Stubbing an unmistakable name proves the
        // service reads it rather than hardcoding one of the real algorithm names.
        stubSolver(new KnapsackSolution("NOT_A_REAL_ALGORITHM", List.of(0, 1), 1500L, 32000L));

        service.optimize(assignmentExample());

        assertThat(capturedRun().getAlgorithmUsed()).isEqualTo("NOT_A_REAL_ALGORITHM");
    }

    @Test
    @DisplayName("a second run records the name its own solution carried")
    void recordsTheAlgorithmNameOfEachRunSeparately() {
        stubSolver(new KnapsackSolution("BRANCH_AND_BOUND", List.of(0, 1), 1500L, 32000L));

        service.optimize(assignmentExample());

        assertThat(capturedRun().getAlgorithmUsed()).isEqualTo("BRANCH_AND_BOUND");
    }

    @Test
    @DisplayName("minor-unit totals are converted back to currency amounts on the run")
    void convertsTotalsBackFromMinorUnits() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getTotalRequestedAmount()).isEqualByComparingTo("15.00");
        assertThat(capturedRun().getTotalFeeRevenue()).isEqualByComparingTo("320.00");
    }

    @Test
    @DisplayName("the run is timestamped from the injected clock rather than the wall clock")
    void timestampsFromTheInjectedClock() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("the response reports the accepted subscriptions only")
    void responseReportsAcceptedSubscriptionsOnly() {
        stubSolver(ASSIGNMENT_SOLUTION);

        OptimizationResultResponse response = service.optimize(assignmentExample());

        assertThat(response.acceptedSubscriptions())
                .extracting(AcceptedSubscription::investorName)
                .containsExactly("Investor A", "Investor B");
    }

    @Test
    @DisplayName("a run in which nothing fits is still persisted with all its rejected candidates")
    void persistsRunWhenNothingIsSelected() {
        stubSolver(KnapsackSolution.empty(SOLUTION_ALGORITHM_NAME));

        OptimizationResultResponse response = service.optimize(assignmentExample());

        assertThat(response.acceptedSubscriptions()).isEmpty();
        assertThat(response.totalRequestedAmount()).isEqualByComparingTo("0.00");
        assertThat(response.totalFeeRevenue()).isEqualByComparingTo("0.00");

        OptimizationRun run = capturedRun();
        assertThat(run.getSubscriptions()).hasSize(4);
        assertThat(run.getSubscriptions()).extracting(SubscriptionRequest::isAccepted).containsOnly(false);
        assertThat(run.getAcceptedCount()).isZero();
        assertThat(run.getCandidateCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("an amount finer than a minor unit is refused before any solving happens")
    void refusesExcessPrecisionBeforeSolving() {
        OptimizeRequest request = new OptimizeRequest(
                new BigDecimal("15"),
                List.of(new SubscriptionRequestPayload(
                        "Investor A", new BigDecimal("5.123"), new BigDecimal("120"))),
                true);

        assertThatExceptionOfType(InvalidSubscriptionInputException.class)
                .isThrownBy(() -> service.optimize(request));

        verify(solver, never()).solve(anyList(), anyLong());
        verifyNoInteractions(runRepository);
    }

    // --- carry-forward ---------------------------------------------------------------

    @Test
    @DisplayName("eligible carried-forward candidates are appended to the pool after the new ones")
    void appendsCarriedCandidatesAfterNewOnes() {
        SubscriptionRequest carried = declinedCandidate(
                priorRun(UUID.randomUUID()), "Investor E", new BigDecimal("3"), new BigDecimal("80"), 2);
        when(subscriptionRequestRepository.findEligibleCarryForwardCandidates(any(Pageable.class)))
                .thenReturn(List.of(carried));
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedItems()).hasSize(5);
        assertThat(capturedItems().get(4).index()).isEqualTo(4);
        assertThat(capturedItems().get(4).weight()).isEqualTo(300L);
        assertThat(capturedItems().get(4).value()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("the pooled candidates, new and carried together, are solved in a single call")
    void solvesThePoolInOneCall() {
        SubscriptionRequest carried = declinedCandidate(
                priorRun(UUID.randomUUID()), "Investor E", new BigDecimal("3"), new BigDecimal("80"), 2);
        when(subscriptionRequestRepository.findEligibleCarryForwardCandidates(any(Pageable.class)))
                .thenReturn(List.of(carried));
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        verify(solver, times(1)).solve(anyList(), anyLong());
    }

    @Test
    @DisplayName("includeCarriedForward false means the repository is never queried and the pool is the new candidates alone")
    void excludesCarriedForwardCandidatesWhenDisabled() {
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample(false));

        assertThat(capturedItems()).hasSize(4);
        verifyNoInteractions(subscriptionRequestRepository);
    }

    @Test
    @DisplayName("an absent includeCarriedForward field behaves as true")
    void absentIncludeCarriedForwardBehavesAsTrue() {
        OptimizeRequest request = assignmentExample(null);
        assertThat(request.includeCarriedForward()).isTrue();
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(request);

        verify(subscriptionRequestRepository).findEligibleCarryForwardCandidates(any(Pageable.class));
    }

    @Test
    @DisplayName("candidate_count counts the whole pool, new candidates and carried ones together")
    void candidateCountCountsTheWholePool() {
        SubscriptionRequest carried = declinedCandidate(
                priorRun(UUID.randomUUID()), "Investor E", new BigDecimal("3"), new BigDecimal("80"), 2);
        when(subscriptionRequestRepository.findEligibleCarryForwardCandidates(any(Pageable.class)))
                .thenReturn(List.of(carried));
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        assertThat(capturedRun().getCandidateCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("a row built from a carried candidate has carriedFrom set; a row from a new candidate has it null")
    void tracksCarriedFromOnlyOnCarriedRows() {
        SubscriptionRequest carried = declinedCandidate(
                priorRun(UUID.randomUUID()), "Investor E", new BigDecimal("3"), new BigDecimal("80"), 2);
        when(subscriptionRequestRepository.findEligibleCarryForwardCandidates(any(Pageable.class)))
                .thenReturn(List.of(carried));
        stubSolver(ASSIGNMENT_SOLUTION);

        service.optimize(assignmentExample());

        List<SubscriptionRequest> subscriptions = capturedRun().getSubscriptions();
        assertThat(subscriptions).hasSize(5);
        assertThat(subscriptions.subList(0, 4)).extracting(SubscriptionRequest::getCarriedFrom).containsOnlyNulls();
        assertThat(subscriptions.get(4).getCarriedFrom()).isSameAs(carried);
    }

    // --- findByRequestId -----------------------------------------------------------

    @Test
    @DisplayName("a stored run is returned with only the subscriptions it accepted")
    void returnsStoredRunWithAcceptedSubscriptions() {
        UUID requestId = UUID.randomUUID();
        when(runRepository.findByIdWithSubscriptions(requestId)).thenReturn(Optional.of(storedRun(requestId)));

        OptimizationResultResponse response = service.findByRequestId(requestId);

        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.acceptedSubscriptions())
                .extracting(AcceptedSubscription::investorName)
                .containsExactly("Investor A", "Investor B");
        assertThat(response.totalRequestedAmount()).isEqualByComparingTo("15.00");
        assertThat(response.totalFeeRevenue()).isEqualByComparingTo("320.00");
        assertThat(response.createdAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("asking for a run that does not exist fails and names the id that was asked for")
    void failsWhenRunIsUnknown() {
        UUID requestId = UUID.randomUUID();
        when(runRepository.findByIdWithSubscriptions(requestId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(OptimizationRunNotFoundException.class)
                .isThrownBy(() -> service.findByRequestId(requestId))
                .withMessageContaining(requestId.toString());
    }

    // --- findAll -------------------------------------------------------------------

    @Test
    @DisplayName("the listing summarises each run from its stored counts without loading its subscriptions")
    void summarisesRunsFromStoredCountsAlone() {
        UUID requestId = UUID.randomUUID();
        OptimizationRun run = spy(storedRun(requestId));
        Page<OptimizationRun> page = new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1);
        when(runRepository.findAllByOrderByCreatedAtDescIdDesc(any())).thenReturn(page);

        PagedResponse<OptimizationRunSummary> response = service.findAll(PageRequest.of(0, 20));

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);

        OptimizationRunSummary summary = response.content().getFirst();
        assertThat(summary.requestId()).isEqualTo(requestId);
        assertThat(summary.maxCapacity()).isEqualByComparingTo("15.00");
        assertThat(summary.totalRequestedAmount()).isEqualByComparingTo("15.00");
        assertThat(summary.totalFeeRevenue()).isEqualByComparingTo("320.00");
        assertThat(summary.acceptedCount()).isEqualTo(2);
        assertThat(summary.candidateCount()).isEqualTo(4);
        assertThat(summary.createdAt()).isEqualTo(FIXED_NOW);

        verify(run, never()).getSubscriptions();
    }

    // --- fixtures and captors ------------------------------------------------------

    private void stubSolver(KnapsackSolution solution) {
        when(solver.solve(anyList(), anyLong())).thenReturn(solution);
        // The write runs inside a TransactionTemplate, which asks the manager for a
        // status and hands it to the callback; a bare mock would supply null.
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // A default mock returns null and the service maps the saved entity into its
        // response, so save() must hand back what it was given.
        when(runRepository.save(any(OptimizationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static OptimizeRequest assignmentExample() {
        return assignmentExample(true);
    }

    private static OptimizeRequest assignmentExample(Boolean includeCarriedForward) {
        return new OptimizeRequest(
                new BigDecimal("15"),
                List.of(
                        new SubscriptionRequestPayload("Investor A", new BigDecimal("5"), new BigDecimal("120")),
                        new SubscriptionRequestPayload("Investor B", new BigDecimal("10"), new BigDecimal("200")),
                        new SubscriptionRequestPayload("Investor C", new BigDecimal("3"), new BigDecimal("80")),
                        new SubscriptionRequestPayload("Investor D", new BigDecimal("8"), new BigDecimal("160"))),
                includeCarriedForward);
    }

    /** A run from an earlier funding window, used only to give a carried candidate a parent. */
    private static OptimizationRun priorRun(UUID requestId) {
        return new OptimizationRun(
                requestId, new BigDecimal("15"), new BigDecimal("12"), new BigDecimal("240"),
                2, 4, SOLUTION_ALGORITHM_NAME, FIXED_NOW.minusSeconds(3600));
    }

    /** A declined candidate belonging to {@code priorRun}, eligible to be carried forward. */
    private static SubscriptionRequest declinedCandidate(
            OptimizationRun priorRun, String investorName, BigDecimal amount, BigDecimal fee, int inputIndex) {
        SubscriptionRequest candidate = new SubscriptionRequest(investorName, amount, fee, false, inputIndex);
        priorRun.addSubscription(candidate);
        return candidate;
    }

    /** A run as it comes back from the repository; ids of never-persisted children stay null. */
    private static OptimizationRun storedRun(UUID requestId) {
        OptimizationRun run = new OptimizationRun(
                requestId,
                new BigDecimal("15"),
                new BigDecimal("15.00"),
                new BigDecimal("320.00"),
                2,
                4,
                SOLUTION_ALGORITHM_NAME,
                FIXED_NOW);
        run.addSubscription(new SubscriptionRequest(
                "Investor A", new BigDecimal("5"), new BigDecimal("120"), true, 0));
        run.addSubscription(new SubscriptionRequest(
                "Investor B", new BigDecimal("10"), new BigDecimal("200"), true, 1));
        run.addSubscription(new SubscriptionRequest(
                "Investor C", new BigDecimal("3"), new BigDecimal("80"), false, 2));
        run.addSubscription(new SubscriptionRequest(
                "Investor D", new BigDecimal("8"), new BigDecimal("160"), false, 3));
        return run;
    }

    private List<KnapsackItem> capturedItems() {
        ArgumentCaptor<List<KnapsackItem>> items = ArgumentCaptor.captor();
        verify(solver).solve(items.capture(), anyLong());
        return items.getValue();
    }

    private long capturedCapacity() {
        ArgumentCaptor<Long> capacity = ArgumentCaptor.captor();
        verify(solver).solve(anyList(), capacity.capture());
        return capacity.getValue();
    }

    private OptimizationRun capturedRun() {
        ArgumentCaptor<OptimizationRun> run = ArgumentCaptor.captor();
        verify(runRepository).save(run.capture());
        return run.getValue();
    }
}
