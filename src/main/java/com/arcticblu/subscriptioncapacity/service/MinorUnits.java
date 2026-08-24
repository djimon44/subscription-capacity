package com.arcticblu.subscriptioncapacity.service;

import com.arcticblu.subscriptioncapacity.domain.CurrencyScale;

import java.math.BigDecimal;

/**
 * Converts between decimal currency amounts and the indivisible integer minor
 * units the solver operates on.
 *
 * <p>Scaling is exact in both directions: {@code movePointRight} performs no
 * rounding, and {@code longValueExact} throws rather than truncating.
 */
final class MinorUnits {

    static final int SCALE = CurrencyScale.SCALE;

    private MinorUnits() {
    }

    static long toMinorUnits(BigDecimal amount, String fieldName) {
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > SCALE) {
            throw new InvalidSubscriptionInputException(
                    "%s must have at most %d decimal places: %s".formatted(fieldName, SCALE, amount.toPlainString()));
        }
        try {
            return normalized.movePointRight(SCALE).longValueExact();
        } catch (ArithmeticException overflow) {
            throw new InvalidSubscriptionInputException(
                    "%s is too large to process: %s".formatted(fieldName, amount.toPlainString()));
        }
    }

    static BigDecimal toDecimal(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, SCALE);
    }
}