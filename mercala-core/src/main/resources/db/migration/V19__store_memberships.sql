-- Multiple stores per account (HAL-556). A membership row is "this user belongs to
-- this store as this role"; app_user.tenant_id narrows to mean the ACTIVE store —
-- the one the JWT carries — while memberships are the full set.

CREATE TABLE store_membership (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id),
    tenant_id  UUID        NOT NULL REFERENCES tenant (id),
    role       VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_membership_user ON store_membership (user_id);

-- Every existing user's single store becomes their first membership. Their
-- tenant_id stays put — it is already the store they'd expect to be active.
INSERT INTO store_membership (id, user_id, tenant_id, role)
SELECT gen_random_uuid(), id, tenant_id, role
FROM app_user
WHERE tenant_id IS NOT NULL;

-- No RLS policy on purpose: memberships are queried by user, before any tenant is
-- selected — the same reason tenantless app_user rows sit outside the policy.
