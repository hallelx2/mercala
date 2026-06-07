-- Identity domain: tenants (stores) and their users.

CREATE TABLE tenant (
    id         UUID PRIMARY KEY,
    slug       VARCHAR(63)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- "user" is a reserved word in Postgres, so the table is named app_user.
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_tenant_email UNIQUE (tenant_id, email)
);

CREATE INDEX idx_app_user_tenant_id ON app_user (tenant_id);
