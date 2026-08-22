package com.arcticblu.subscriptioncapacity.web.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * A single investor subscription request, used for both input and output.
 *
 * @param investorName    human-readable investor label
 * @param requestedAmount capital the investor wants to commit
 * @param feeRevenue      management fee earned if the request is accepted
 */
public record SubscriptionRequestPayload(

        @NotBlank(message = "investorName must not be blank")
        @Size(max = 255, message = "investorName must not exceed 255 characters")
        String investorName,

        @NotNull(message = "requestedAmount is required")
        @PositiveOrZero(message = "requestedAmount must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "requestedAmount must have at most 2 decimal places")
        BigDecimal requestedAmount,

        @NotNull(message = "feeRevenue is required")
        @PositiveOrZero(message = "feeRevenue must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "feeRevenue must have at most 2 decimal places")
        BigDecimal feeRevenue) {
}