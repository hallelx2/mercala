-- V13__media.sql
-- Media Domain: Product Image references.

CREATE TABLE product_image (
    id         UUID PRIMARY KEY,
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),
    product_id UUID         NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    url        VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_image_tenant_id ON product_image (tenant_id);
CREATE INDEX idx_product_image_product_id ON product_image (product_id);

-- Enable Row-Level Security (RLS)
ALTER TABLE product_image ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_image FORCE ROW LEVEL SECURITY;

-- Define RLS Policy for mercala_app
CREATE POLICY tenant_isolation_policy ON product_image
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Explicitly grant permissions to mercala_app
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mercala_app;
