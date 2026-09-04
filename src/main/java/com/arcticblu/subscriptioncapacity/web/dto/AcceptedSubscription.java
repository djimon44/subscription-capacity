package com.arcticblu.subscriptioncapacity.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

public record AcceptedSubscription(
        String investorName,
        BigDecimal requestedAmount,
        BigDecimal feeRevenue,
        boolean carriedForward,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID originalRequestId) {
}
