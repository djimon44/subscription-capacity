package com.arcticblu.subscriptioncapacity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The scale at which currency amounts are stored and reported.
 *
 * <p>Amounts arrive from the API with whatever scale the caller supplied — {@code 5},
 * {@code 5.0}, and {@code 5.00} are all accepted — but they are persisted in
 * {@code NUMERIC(19,2)} columns and must render identically whether a response is
 * built from an in-memory entity or read back from the database.
 */
public final class CurrencyScale {

    /** Two decimal places: currency is counted in hundredths of a unit. */
    public static final int SCALE = 2;

    private CurrencyScale() {
    }

    /**
     * Returns the amount at the canonical scale.
     *
     * <p>Uses {@link RoundingMode#UNNECESSARY} deliberately: callers validate the
     * decimal places before constructing an entity, so a value needing rounding is a
     * programming error and should fail loudly rather than silently lose a fraction
     * of a currency unit.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}