package com.mercala.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.mercala.AbstractIntegrationTest;
import com.mercala.platform.security.JwtService;
import com.mercala.platform.multitenancy.TenantContext;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void shouldIsolateQueriesByTenantFilter() throws Exception {
        // 1. Seed Tenant A with Owner A and Staff A
        Tenant tenantA = tenantRepository.save(new Tenant("tenant-a", "Tenant A"));
        AppUser ownerA = userRepository.save(new AppUser(
                tenantA.getId(), "owner-a@example.com", passwordEncoder.encode("password"), Role.MERCHANT_OWNER));
        userRepository.save(new AppUser(
                tenantA.getId(), "staff-a@example.com", passwordEncoder.encode("password"), Role.MERCHANT_STAFF));

        // 2. Seed Tenant B with Owner B and Staff B
        Tenant tenantB = tenantRepository.save(new Tenant("tenant-b", "Tenant B"));
        AppUser ownerB = userRepository.save(new AppUser(
                tenantB.getId(), "owner-b@example.com", passwordEncoder.encode("password"), Role.MERCHANT_OWNER));
        userRepository.save(new AppUser(
                tenantB.getId(), "staff-b@example.com", passwordEncoder.encode("password"), Role.MERCHANT_STAFF));

        // 3. Mint token for Owner A
        String tokenA = "Bearer " + jwtService.issue(ownerA);

        // 4. Request Tenant A's users using Owner A's token
        // Should return ONLY Tenant A's users
        mockMvc.perform(get("/api/tenants/tenant-a/users")
                        .header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].email", containsInAnyOrder("owner-a@example.com", "staff-a@example.com")));

        // 5. Request Tenant B's users using Owner A's token
        // This is a cross-tenant access attempt and should fail with 403 Forbidden.
        mockMvc.perform(get("/api/tenants/tenant-b/users")
                        .header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldEnforceRowLevelSecurityEvenWhenFilterDisabled() throws Exception {
        // 1. Seed Tenant A and Tenant B (runs as owner 'mercala', bypassing RLS)
        Tenant tenantA = tenantRepository.save(new Tenant("tenant-a-rls", "Tenant A RLS"));
        userRepository.save(new AppUser(
                tenantA.getId(), "rls-a@example.com", passwordEncoder.encode("password"), Role.MERCHANT_STAFF));

        Tenant tenantB = tenantRepository.save(new Tenant("tenant-b-rls", "Tenant B RLS"));
        userRepository.save(new AppUser(
                tenantB.getId(), "rls-b@example.com", passwordEncoder.encode("password"), Role.MERCHANT_STAFF));

        // 2. Open a direct JDBC connection as the restricted 'mercala_app' role
        String jdbcUrl = POSTGRES.getJdbcUrl();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, "mercala_app", "mercala_app")) {
            // Disable auto-commit to ensure transaction-scoped set_config holds during the query
            conn.setAutoCommit(false);

            // Set app.current_tenant to Tenant A
            try (var stmt = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                stmt.setString(1, tenantA.getId().toString());
                stmt.execute();
            }

            // Query app_user directly. RLS must filter out Tenant B rows at the DB layer.
            try (var stmt = conn.prepareStatement("SELECT email FROM app_user")) {
                try (var rs = stmt.executeQuery()) {
                    java.util.Set<String> emails = new java.util.HashSet<>();
                    while (rs.next()) {
                        emails.add(rs.getString("email"));
                    }
                    org.junit.jupiter.api.Assertions.assertTrue(emails.contains("rls-a@example.com"));
                    org.junit.jupiter.api.Assertions.assertFalse(emails.contains("rls-b@example.com"));
                }
            }
            conn.rollback();
        }
    }
}
