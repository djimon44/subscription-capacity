package com.arcticblu.subscriptioncapacity.service;

import com.arcticblu.subscriptioncapacity.algorithm.KnapsackItem;
import com.arcticblu.subscriptioncapacity.algorithm.KnapsackSolution;
import com.arcticblu.subscriptioncapacity.algorithm.KnapsackSolver;
import com.arcticblu.subscriptioncapacity.domain.OptimizationRun;
import com.arcticblu.subscriptioncapacity.domain.SubscriptionRequest;
import com.arcticblu.subscriptioncapacity.repository.OptimizationRunRepository;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationResultResponse;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizationRunSummary;
import com.arcticblu.subscriptioncapacity.web.dto.OptimizeRequest;
import com.arcticblu.subscriptioncapacity.web.dto.PagedResponse;
import com.arcticblu.subscriptioncapacity.web.dto.SubscriptionRequestPayload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SubscriptionOptimizationService {

    private final KnapsackSolver solver;
    private final OptimizationRunRepository runRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SubscriptionOptimizationService(KnapsackSolver solver,
                                           OptimizationRunRepository runRepository,
                                           Clock clock,
                                           PlatformTransactionManager transactionManager) {
        this.solver = solver;
        this.runRepository = runRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs the allocation, persists both the candidates and the outcome, and
     * returns the accepted subscriptions.
     *
     * <p>Deliberately not annotated {@code @Transactional}. Scaling the amounts and
     * running the knapsack search touch no database at all, yet a method-level
     * annotation would check a pooled connection out on entry and hold it for the
     * whole call. The search can take a noticeable time on a large problem, so a
     * handful of concurrent optimisations would exhaust the pool while doing no
     * database work. Only the write is wrapped, programmatically via
     * {@link TransactionTemplate}: annotating a private helper instead would be
     * silently ignored, since a self-invocation never passes through the proxy that
     * applies the annotation.
     */
    public OptimizationResultResponse optimize(OptimizeRequest request) {
        List<SubscriptionRequestPayload> candidates = request.availableSubscriptions();

        long capacity = MinorUnits.toMinorUnits(request.maxCapacity(), "maxCapacity");

        List<KnapsackItem> items = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            SubscriptionRequestPayload candidate = candidates.get(i);
            items.add(new KnapsackItem(
                    i,
                    MinorUnits.toMinorUnits(candidate.requestedAmount(), "requestedAmount"),
                    MinorUnits.toMinorUnits(candidate.feeRevenue(), "feeRevenue")));
        }

        KnapsackSolution solution = solver.solve(items, capacity);
        Set<Integer> acceptedIndices = Set.copyOf(solution.selectedIndices());

        OptimizationRun run = new OptimizationRun(
                UUID.randomUUID(),
                request.maxCapacity(),
                MinorUnits.toDecimal(solution.totalWeight()),
                MinorUnits.toDecimal(solution.totalValue()),
                solution.selectedIndices().size(),
                candidates.size(),
                solution.algorithmName(),
                Instant.now(clock));

        // Every candidate is persisted, accepted or not: the audit trail must show
        // which investors applied and were declined, not only the winners.
        for (int i = 0; i < candidates.size(); i++) {
            SubscriptionRequestPayload candidate = candidates.get(i);
            run.addSubscription(new SubscriptionRequest(
                    candidate.investorName(),
                    candidate.requestedAmount(),
                    candidate.feeRevenue(),
                    acceptedIndices.contains(i),
                    i));
        }

        // The mapping stays inside the transaction: it walks the run's subscriptions
        // association, which must not be touched once the persistence context closes.
        return transactionTemplate.execute(status -> toResultResponse(runRepository.save(run)));
    }

    @Transactional(readOnly = true)
    public OptimizationResultResponse findByRequestId(UUID requestId) {
        return runRepository.findByIdWithSubscriptions(requestId)
                .map(this::toResultResponse)
                .orElseThrow(() -> new OptimizationRunNotFoundException(requestId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<OptimizationRunSummary> findAll(Pageable pageable) {
        Page<OptimizationRun> runs = runRepository.findAllByOrderByCreatedAtDescIdDesc(pageable);
        return PagedResponse.from(runs, this::toSummary);
    }

    private OptimizationResultResponse toResultResponse(OptimizationRun run) {
        List<SubscriptionRequestPayload> accepted = run.getSubscriptions().stream()
                .filter(SubscriptionRequest::isAccepted)
                .map(subscription -> new SubscriptionRequestPayload(
                        subscription.getInvestorName(),
                        subscription.getRequestedAmount(),
                        subscription.getFeeRevenue()))
                .toList();

        return new OptimizationResultResponse(
                run.getId(),
                accepted,
                run.getTotalRequestedAmount(),
                run.getTotalFeeRevenue(),
                run.getCreatedAt());
    }

    private OptimizationRunSummary toSummary(OptimizationRun run) {
        return new OptimizationRunSummary(
                run.getId(),
                run.getMaxCapacity(),
                run.getTotalRequestedAmount(),
                run.getTotalFeeRevenue(),
                run.getCandidateCount(),
                run.getAcceptedCount(),
                run.getCreatedAt());
    }
}