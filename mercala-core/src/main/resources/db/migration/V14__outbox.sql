-- V14__outbox.sql
-- Transactional outbox table for reliable event publishing.
-- Events are written here in the same DB transaction as the state change,
-- then asynchronously relayed to Kafka by OutboxRelay.

CREATE TABLE outbox_event (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    tenant_id      UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- Index for the relay poller: find unpublished events ordered by creation time
CREATE INDEX idx_outbox_event_unpublished ON outbox_event (created_at)
    WHERE published_at IS NULL;

-- Index for tenant-scoped queries and housekeeping
CREATE INDEX idx_outbox_event_tenant_id ON outbox_event (tenant_id);
