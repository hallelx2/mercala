-- V20__media_asset.sql
-- Media Domain: images the merchant uploaded themselves.
--
-- Distinct from product_image, which records the URL of an image that has been attached to
-- a product. An asset is the raw upload: it exists before anyone has decided what it is for,
-- may be enhanced into a different image, and may never be attached to anything at all.
-- Folding the two together would mean an unattached upload had to invent a product_id.

CREATE TABLE media_asset (
    id           UUID PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES tenant (id),
    product_id   UUID         REFERENCES product (id) ON DELETE SET NULL,
    url          VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    original_name VARCHAR(255),
    uploaded_by  UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_media_asset_tenant_id ON media_asset (tenant_id);
CREATE INDEX idx_media_asset_product_id ON media_asset (product_id);

-- Row-Level Security: the floor under the Hibernate tenant filter, not a substitute for it.
ALTER TABLE media_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_asset FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON media_asset
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mercala_app;
