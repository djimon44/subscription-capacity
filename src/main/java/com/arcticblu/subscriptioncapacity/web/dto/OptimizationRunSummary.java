package com.arcticblu.subscriptioncapacity.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Summary of a past run for the audit trail listing. Deliberately omits the
 * individual subscriptions: both counts are stored on the run itself, so the
 * listing needs no access to the subscriptions association at all.
 *
 * @param candidateCount total number of subscriptions submitted to the run
 * @param acceptedCount  how many of those the run accepted
 */
public record OptimizationRunSummary(
        UUID requestId,
        BigDecimal maxCapacity,
        BigDecimal totalRequestedAmount,
        BigDecimal totalFeeRevenue,
        int candidateCount,
        int acceptedCount,
        Instant createdAt) {
}