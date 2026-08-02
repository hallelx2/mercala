-- Identity first, store second (HAL-552): a person signs up with just name, email
-- and password, and creates their store later from the dashboard. Until then their
-- user row has no tenant.

ALTER TABLE app_user ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE app_user ADD COLUMN name VARCHAR(255);

-- Postgres treats NULLs as distinct in unique constraints, so uq_app_user_tenant_email
-- stops guarding tenantless rows. This partial index restores uniqueness for them:
-- one pre-store account per email. Per-tenant duplicates remain allowed, as before.
CREATE UNIQUE INDEX uq_app_user_email_no_tenant ON app_user (email) WHERE tenant_id IS NULL;

-- RLS note: the tenant_isolation_policy on app_user hides tenantless rows from the
-- mercala_app role. That is correct — those rows are only touched by the auth paths,
-- which run before any tenant context exists and outside that role.
