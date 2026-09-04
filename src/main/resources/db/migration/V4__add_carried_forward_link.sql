-- V4: carry-forward link on subscription_request

ALTER TABLE subscription_request
    ADD COLUMN carried_from_id BIGINT NULL;

-- no ON DELETE CASCADE here
ALTER TABLE subscription_request
    ADD CONSTRAINT fk_subscription_request_carried_from
        FOREIGN KEY (carried_from_id) REFERENCES subscription_request (id);

-- Each declined row may be copied into a later run at most once
ALTER TABLE subscription_request
    ADD CONSTRAINT uq_subscription_request_carried_from
        UNIQUE (carried_from_id);

-- Index for the carried_from_id column to improve query performance.
CREATE INDEX idx_subscription_request_declined
    ON subscription_request (accepted)
    WHERE accepted = false;
