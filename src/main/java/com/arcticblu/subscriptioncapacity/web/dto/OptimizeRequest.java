package com.arcticblu.subscriptioncapacity.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Input payload for an optimization run.
 *
 * @param maxCapacity            total remaining subscription capacity for the window
 * @param availableSubscriptions candidate investor requests, at least one
 */
public record OptimizeRequest(

        @NotNull(message = "maxCapacity is required")
        @PositiveOrZero(message = "maxCapacity must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "maxCapacity must have at most 2 decimal places")
        BigDecimal maxCapacity,

        @NotNull(message = "availableSubscriptions is required")
        @NotEmpty(message = "availableSubscriptions must contain at least one entry")
        @Size(max = 1000, message = "availableSubscriptions must not exceed 1000 entries")
        @Valid
        List<SubscriptionRequestPayload> availableSubscriptions) {
}