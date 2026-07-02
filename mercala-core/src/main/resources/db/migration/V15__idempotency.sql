-- V15__idempotency.sql
-- Idempotency tracking table to record successfully processed event IDs.

CREATE TABLE processed_event (
    event_id   UUID         PRIMARY KEY,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Explicitly grant permissions to mercala_app
GRANT SELECT, INSERT ON processed_event TO mercala_app;
