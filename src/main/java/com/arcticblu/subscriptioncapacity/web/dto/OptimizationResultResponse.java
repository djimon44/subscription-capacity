package com.arcticblu.subscriptioncapacity.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of an optimization run, matching the payload in the assignment.
 */
public record OptimizationResultResponse(
        UUID requestId,
        List<SubscriptionRequestPayload> acceptedSubscriptions,
        BigDecimal totalRequestedAmount,
        BigDecimal totalFeeRevenue,
        Instant createdAt) {

    public OptimizationResultResponse {
        acceptedSubscriptions = List.copyOf(acceptedSubscriptions);
    }
}