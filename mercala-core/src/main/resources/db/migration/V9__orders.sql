-- V9__orders.sql
-- Create orders and order_line tables with tenant-scoped RLS policies

CREATE TABLE orders (
    id              UUID            PRIMARY KEY,
    tenant_id       UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    status          VARCHAR(30)     NOT NULL,
    total_amount    NUMERIC(12, 2)  NOT NULL,
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE order_line (
    id          UUID            PRIMARY KEY,
    tenant_id   UUID            NOT NULL,
    order_id    UUID            NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    variant_id  UUID            NOT NULL,
    quantity    INTEGER         NOT NULL CONSTRAINT chk_order_line_quantity CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2)  NOT NULL CONSTRAINT chk_order_line_price CHECK (unit_price >= 0),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_line_order_variant UNIQUE (order_id, variant_id)
);

-- Indexes for performance
CREATE INDEX idx_orders_tenant_user ON orders (tenant_id, user_id);
CREATE INDEX idx_orders_tenant_idempotency ON orders (tenant_id, idempotency_key);
CREATE INDEX idx_order_line_order ON order_line (order_id);

-- Enable Row-Level Security (RLS) on both tables
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;

ALTER TABLE order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line FORCE ROW LEVEL SECURITY;

-- Define tenant isolation policy for orders
CREATE POLICY tenant_isolation_policy ON orders
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Define tenant isolation policy for order_line
CREATE POLICY tenant_isolation_policy ON order_line
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Explicitly grant privileges to mercala_app role
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mercala_app;
