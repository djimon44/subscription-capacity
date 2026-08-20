-- V1: optimization_run
-- One row per optimization run: the capacity input and the aggregate results.

CREATE TABLE optimization_run (
    id                     UUID           PRIMARY KEY,
    max_capacity           NUMERIC(19, 2) NOT NULL,
    total_requested_amount NUMERIC(19, 2) NOT NULL,
    total_fee_revenue      NUMERIC(19, 2) NOT NULL,
    algorithm_used         VARCHAR(32)    NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL,

    CONSTRAINT chk_run_max_capacity_non_negative
        CHECK (max_capacity >= 0),
    CONSTRAINT chk_run_totals_non_negative
        CHECK (total_requested_amount >= 0 AND total_fee_revenue >= 0)
);

CREATE INDEX idx_optimization_run_created_at
    ON optimization_run (created_at DESC, id DESC);