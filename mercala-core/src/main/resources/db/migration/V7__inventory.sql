-- V7__inventory.sql
-- Create stock_item table for managing inventory per product variant

CREATE TABLE stock_item (
    id                UUID           PRIMARY KEY,
    tenant_id         UUID           NOT NULL,
    variant_id        UUID           NOT NULL UNIQUE REFERENCES variant (id) ON DELETE CASCADE,
    quantity          INTEGER        NOT NULL DEFAULT 0 CONSTRAINT chk_stock_item_quantity_non_negative CHECK (quantity >= 0),
    reserved_quantity INTEGER        NOT NULL DEFAULT 0 CONSTRAINT chk_stock_item_reserved_non_negative CHECK (reserved_quantity >= 0),
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_stock_item_reserved_le_quantity CHECK (reserved_quantity <= quantity)
);

-- Indexes for performance
CREATE INDEX idx_stock_item_tenant_variant ON stock_item (tenant_id, variant_id);

-- Trigger function to ensure variant belongs to the same tenant as stock_item
CREATE OR REPLACE FUNCTION check_stock_item_tenant_consistency()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM variant v
        JOIN product p ON v.product_id = p.id
        WHERE v.id = NEW.variant_id AND p.tenant_id = NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'Variant % does not belong to tenant %', NEW.variant_id, NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_stock_item_tenant_consistency
AFTER INSERT OR UPDATE ON stock_item
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_stock_item_tenant_consistency();

-- Enable Row-Level Security (RLS) on stock_item
ALTER TABLE stock_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_item FORCE ROW LEVEL SECURITY;

-- Define tenant isolation policy for stock_item
CREATE POLICY tenant_isolation_policy ON stock_item
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Explicitly grant privileges to mercala_app role on the new table
GRANT SELECT, INSERT, UPDATE, DELETE ON stock_item TO mercala_app;
