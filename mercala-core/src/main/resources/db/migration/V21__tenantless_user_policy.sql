-- Self-serve signup (HAL-552) could not work under row-level security.
--
-- V18 let app_user.tenant_id be NULL so a person can exist before their store does, and
-- noted that the tenant_isolation_policy hides those rows from mercala_app because "the
-- auth paths run outside that role". They do not: mercala_app is the only role the
-- application ever connects as, in this repo's compose file and in the deployed stack
-- alike. So INSERT of a tenantless user was refused by the policy's WITH CHECK, and a
-- SELECT during login could never see the row it had to authenticate against.
--
-- The fix is a second policy covering exactly the tenantless rows, rather than widening
-- the tenant policy. Tenant isolation is untouched: a row with a tenant_id is still
-- visible only inside a session carrying that tenant, and a tenantless row belongs to no
-- tenant and so leaks nothing across one.
CREATE POLICY tenantless_user_policy ON app_user
    FOR ALL
    TO mercala_app
    USING (tenant_id IS NULL)
    WITH CHECK (tenant_id IS NULL);
