package com.arcticblu.subscriptioncapacity.repository;

import com.arcticblu.subscriptioncapacity.domain.SubscriptionRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SubscriptionRequestRepository extends JpaRepository<SubscriptionRequest, Long> {

    /**
     * Returns declined candidates eligible to be carried forward into a new run: not
     * accepted, and not already copied into some later run.
     */
    @Query("""
            SELECT s FROM SubscriptionRequest s
            JOIN FETCH s.run r
            WHERE s.accepted = false
              AND NOT EXISTS (SELECT 1 FROM SubscriptionRequest c WHERE c.carriedFrom = s)
            ORDER BY r.createdAt DESC, r.id DESC, s.inputIndex ASC
            """)
    List<SubscriptionRequest> findEligibleCarryForwardCandidates(Pageable pageable);
}
