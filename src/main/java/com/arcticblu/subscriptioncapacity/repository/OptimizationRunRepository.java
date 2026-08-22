package com.arcticblu.subscriptioncapacity.repository;

import com.arcticblu.subscriptioncapacity.domain.OptimizationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OptimizationRunRepository extends JpaRepository<OptimizationRun, UUID> {

    /**
     * Loads a run together with its subscriptions in a single query.
     *
     * <p>The default {@code findById} would fetch the run alone and then issue a
     * second query when the lazily-loaded subscriptions are first touched. Because
     * {@code open-in-view} is disabled, that second query can only happen inside the
     * service transaction; fetching eagerly here keeps the read to one round trip.
     */
    @Query("SELECT run FROM OptimizationRun run LEFT JOIN FETCH run.subscriptions WHERE run.id = :id")
    Optional<OptimizationRun> findByIdWithSubscriptions(UUID id);

    /**
     * Returns runs newest first for the audit trail. Subscriptions are deliberately
     * not fetched: the listing reports only the persisted totals, so loading every
     * child row for every run on the page would be wasted work.
     */
    Page<OptimizationRun> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}