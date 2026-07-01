-- V3__rls.sql
-- Enforce DB-level Row-Level Security (RLS) as layer 2 of defense-in-depth.

-- 1. Create a non-owner database role for the application if not exists
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'mercala_app') THEN
        CREATE ROLE mercala_app WITH LOGIN PASSWORD 'mercala_app';
    END IF;
END $$;

-- 2. Grant permissions on the schema and existing tables to the app role
GRANT USAGE ON SCHEMA public TO mercala_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mercala_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mercala_app;

-- 3. Ensure future tables created by migrations automatically grant access to the app role
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mercala_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO mercala_app;

-- 4. Enable Row-Level Security on tenant-scoped tables
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;

-- 5. Define the tenant isolation policy using 'app.current_tenant' transaction setting
CREATE POLICY tenant_isolation_policy ON app_user
    FOR ALL
    TO mercala_app
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
