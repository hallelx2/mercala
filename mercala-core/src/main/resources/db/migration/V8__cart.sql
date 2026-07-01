-- V8__cart.sql
-- Create cart and cart_line tables with tenant-scoped RLS policies

CREATE TABLE cart (
    id         UUID         PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_tenant_user UNIQUE (tenant_id, user_id)
);

CREATE TABLE cart_line (
    id         UUID         PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    cart_id    UUID         NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    variant_id UUID         NOT NULL REFERENCES variant (id) ON DELETE CASCADE,
    quantity   INTEGER      NOT NULL CONSTRAINT chk_cart_line_quantity CHECK (quantity > 0),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_line_cart_variant UNIQUE (cart_id, variant_id)
);

-- Indexes for performance
CREATE INDEX idx_cart_tenant_user ON cart (tenant_id, user_id);
CREATE INDEX idx_cart_line_cart_variant ON cart_line (cart_id, variant_id);

-- Enable Row-Level Security (RLS) on both tables
ALTER TABLE cart ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart FORCE ROW LEVEL SECURITY;

ALTER TABLE cart_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart_line FORCE ROW LEVEL SECURITY;

-- Define tenant isolation policy for cart
CREATE POLICY tenant_isolation_policy ON cart
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Define tenant isolation policy for cart_line
CREATE POLICY tenant_isolation_policy ON cart_line
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Explicitly grant privileges to mercala_app role
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mercala_app;
