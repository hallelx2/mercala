package com.mercala.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.platform.security.JwtService;

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
                .andExpect(jsonPath("$[0].email").value("owner-a@example.com"))
                .andExpect(jsonPath("$[1].email").value("staff-a@example.com"));

        // 5. Request Tenant B's users using Owner A's token
        // Even though the URL path says "tenant-b", the Hibernate filter forces the query
        // to filter by Owner A's tenant (Tenant A). Thus, it returns Tenant A's users!
        mockMvc.perform(get("/api/tenants/tenant-b/users")
                        .header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].email").value("owner-a@example.com"))
                .andExpect(jsonPath("$[1].email").value("staff-a@example.com"));
    }
}
