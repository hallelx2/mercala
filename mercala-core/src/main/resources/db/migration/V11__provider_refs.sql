-- V11__provider_refs.sql
-- Create provider_refs table with tenant-scoped RLS policies

CREATE TABLE provider_refs (
    id                    UUID            PRIMARY KEY,
    tenant_id             UUID            NOT NULL,
    provider              VARCHAR(50)     NOT NULL,
    connected_account_id  VARCHAR(255),
    public_key            VARCHAR(255),
    secret_key            VARCHAR(255),
    enabled               BOOLEAN         NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_provider_refs_tenant_provider UNIQUE (tenant_id, provider)
);

-- Index for performance
CREATE INDEX idx_provider_refs_tenant ON provider_refs (tenant_id, provider);

-- Enable Row-Level Security (RLS)
ALTER TABLE provider_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE provider_refs FORCE ROW LEVEL SECURITY;

-- Define tenant isolation policy for provider_refs
CREATE POLICY tenant_isolation_policy ON provider_refs
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Explicitly grant privileges to mercala_app role on the provider_refs table
GRANT SELECT, INSERT, UPDATE, DELETE ON provider_refs TO mercala_app;
