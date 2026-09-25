-- SKUs belong to a merchant, not to the platform.
--
-- uq_variant_sku made them unique across every tenant, while the application checked for
-- clashes through a tenant-scoped repository — so the check could not see the row it was
-- meant to find, and the insert failed on the constraint instead. In practice that means
-- the second store to sell a "Navy Linen Shirt" cannot create it at all: the conflict is
-- real, unavoidable, and about somebody else's catalogue.
--
-- variant carries tenant_id now, the way every other tenant-scoped table does. The
-- row-level security policy is left alone — it reaches the tenant through the parent
-- product and remains correct — so this column exists to be indexed, not to be trusted
-- over the product it hangs from.
ALTER TABLE variant ADD COLUMN tenant_id UUID;

UPDATE variant v SET tenant_id = p.tenant_id FROM product p WHERE p.id = v.product_id;

ALTER TABLE variant ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE variant DROP CONSTRAINT uq_variant_sku;
CREATE UNIQUE INDEX uq_variant_tenant_sku ON variant (tenant_id, sku);

GRANT SELECT, INSERT, UPDATE, DELETE ON variant TO mercala_app;
