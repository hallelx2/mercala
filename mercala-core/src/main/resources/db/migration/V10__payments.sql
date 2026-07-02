-- V10__payments.sql
-- Create payments table with tenant-scoped RLS policies

-- Ensure orders table has a unique key on (id, tenant_id) to support composite foreign key
ALTER TABLE orders ADD CONSTRAINT uq_orders_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE payments (
    id                  UUID            PRIMARY KEY,
    tenant_id           UUID            NOT NULL,
    order_id            UUID            NOT NULL,
    amount              NUMERIC(12, 2)  NOT NULL CONSTRAINT chk_payments_amount CHECK (amount >= 0),
    currency            VARCHAR(10)     NOT NULL,
    provider            VARCHAR(50)     NOT NULL,
    status              VARCHAR(30)     NOT NULL,
    provider_reference  VARCHAR(255),
    idempotency_key     VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_payments_orders_tenant FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id) ON DELETE RESTRICT
);

-- Indexes for performance
CREATE INDEX idx_payments_tenant_order ON payments (tenant_id, order_id);
CREATE INDEX idx_payments_tenant_idempotency ON payments (tenant_id, idempotency_key);

-- Enable Row-Level Security (RLS)
ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;

-- Define tenant isolation policy for payments
CREATE POLICY tenant_isolation_policy ON payments
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Explicitly grant privileges to mercala_app role on the payments table
GRANT SELECT, INSERT, UPDATE, DELETE ON payments TO mercala_app;
