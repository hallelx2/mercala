-- V4__catalog.sql
-- Catalog Domain: Category, Product, and Variant.

-- 1. Category Table (Self-referencing tree, tenant-scoped)
CREATE TABLE category (
    id         UUID PRIMARY KEY,
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),
    parent_id  UUID         REFERENCES category (id) ON DELETE SET NULL,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_tenant_slug UNIQUE (tenant_id, slug)
);

CREATE INDEX idx_category_tenant_id ON category (tenant_id);
CREATE INDEX idx_category_parent_id ON category (parent_id);

-- 2. Product Table (tenant-scoped)
CREATE TABLE product (
    id          UUID PRIMARY KEY,
    tenant_id   UUID           NOT NULL REFERENCES tenant (id),
    category_id UUID           REFERENCES category (id) ON DELETE SET NULL,
    name        VARCHAR(255)   NOT NULL,
    description TEXT,
    status      VARCHAR(32)    NOT NULL DEFAULT 'ACTIVE',
    price       NUMERIC(19, 4) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_tenant_id ON product (tenant_id);
CREATE INDEX idx_product_category_id ON product (category_id);

-- 3. Variant Table (linked to parent Product)
CREATE TABLE variant (
    id         UUID PRIMARY KEY,
    product_id UUID           NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sku        VARCHAR(100)   NOT NULL CONSTRAINT uq_variant_sku UNIQUE,
    attrs      JSONB,
    price      NUMERIC(19, 4) NOT NULL,
    stock_ref  VARCHAR(255),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_variant_product_id ON variant (product_id);

-- 4. Enable Row-Level Security (RLS) on all three tables
ALTER TABLE category ENABLE ROW LEVEL SECURITY;
ALTER TABLE category FORCE ROW LEVEL SECURITY;

ALTER TABLE product ENABLE ROW LEVEL SECURITY;
ALTER TABLE product FORCE ROW LEVEL SECURITY;

ALTER TABLE variant ENABLE ROW LEVEL SECURITY;
ALTER TABLE variant FORCE ROW LEVEL SECURITY;

-- 5. Define RLS Policies for mercala_app
CREATE POLICY tenant_isolation_policy ON category
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation_policy ON product
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation_policy ON variant
    FOR ALL
    TO mercala_app
    USING (EXISTS (
        SELECT 1 FROM product p
        WHERE p.id = product_id
          AND p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM product p
        WHERE p.id = product_id
          AND p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
    ));

-- 6. Explicitly grant permissions on new tables to mercala_app
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mercala_app;
