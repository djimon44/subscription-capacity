-- V3: denormalized subscription counts on optimization_run, and input_index uniqueness
-- The counts are known when a run is created and a run is never updated afterwards, so
-- storing them here lets the audit listing report "n of m accepted" without touching
-- subscription_request at all.

-- Added nullable first so the statement succeeds against a table that already holds rows.
ALTER TABLE optimization_run
    ADD COLUMN accepted_count  INTEGER,
    ADD COLUMN candidate_count INTEGER;

UPDATE optimization_run r
SET accepted_count  = (SELECT COUNT(*)
                       FROM subscription_request s
                       WHERE s.run_id = r.id
                         AND s.accepted),
    candidate_count = (SELECT COUNT(*)
                       FROM subscription_request s
                       WHERE s.run_id = r.id);

ALTER TABLE optimization_run
    ALTER COLUMN accepted_count SET NOT NULL,
    ALTER COLUMN candidate_count SET NOT NULL;

ALTER TABLE optimization_run
    ADD CONSTRAINT chk_run_counts_non_negative
        CHECK (accepted_count >= 0 AND candidate_count >= 0),
    ADD CONSTRAINT chk_run_accepted_within_candidates
        CHECK (accepted_count <= candidate_count);

-- input_index is the ordering key for the run's subscriptions and the join key back to
-- the solver's item indices, so two rows claiming the same index within one run is a
-- corrupt state rather than merely unusual.
ALTER TABLE subscription_request
    ADD CONSTRAINT uq_subscription_request_run_input_index
        UNIQUE (run_id, input_index);
