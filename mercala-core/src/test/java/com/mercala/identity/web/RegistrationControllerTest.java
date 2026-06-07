package com.mercala.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.TenantRepository;

@AutoConfigureMockMvc
class RegistrationControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldCreateTenantAndOwner() throws Exception {
        String slug = "test-store";
        String email = "owner@test.store";
        String password = "supersecretpassword";

        String json = """
                {
                    "slug": "%s",
                    "name": "Test Store",
                    "ownerEmail": "%s",
                    "ownerPassword": "%s"
                }
                """.formatted(slug, email, password);

        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.name").value("Test Store"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        var tenant = tenantRepository.findBySlug(slug).orElseThrow();
        var user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();

        assertThat(user.getRole()).isEqualTo(Role.MERCHANT_OWNER);
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
    }

    @Test
    void shouldReturn409OnDuplicateTenantSlug() throws Exception {
        String slug = "dup-store-test";
        String json = """
                {
                    "slug": "%s",
                    "name": "Dup Store",
                    "ownerEmail": "owner@dup.store",
                    "ownerPassword": "supersecretpassword"
                }
                """.formatted(slug);

        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Tenant slug already exists: " + slug));
    }

    @Test
    void shouldAddUserToTenant() throws Exception {
        // Create tenant first
        String slug = "user-store";
        String tenantJson = """
                {
                    "slug": "%s",
                    "name": "User Store",
                    "ownerEmail": "owner@user.store",
                    "ownerPassword": "supersecretpassword"
                }
                """.formatted(slug);

        mockMvc.perform(post("/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantJson));

        // Add user
        String email = "staff@user.store";
        String password = "staffpassword";
        String userJson = """
                {
                    "email": "%s",
                    "password": "%s",
                    "role": "MERCHANT_STAFF"
                }
                """.formatted(email, password);

        mockMvc.perform(post("/api/tenants/" + slug + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("MERCHANT_STAFF"));

        var tenant = tenantRepository.findBySlug(slug).orElseThrow();
        AppUser user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.MERCHANT_STAFF);
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
    }

    @Test
    void shouldReturn404OnUnknownTenantWhenAddingUser() throws Exception {
        String userJson = """
                {
                    "email": "staff@unknown.store",
                    "password": "staffpassword",
                    "role": "MERCHANT_STAFF"
                }
                """;

        mockMvc.perform(post("/api/tenants/unknown-store/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Tenant not found: unknown-store"));
    }

    @Test
    void shouldReturn409OnDuplicateEmailWithinTenant() throws Exception {
        String slug = "dup-email-store";
        String tenantJson = """
                {
                    "slug": "%s",
                    "name": "Dup Email Store",
                    "ownerEmail": "owner@dupemail.store",
                    "ownerPassword": "supersecretpassword"
                }
                """.formatted(slug);

        mockMvc.perform(post("/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tenantJson));

        String email = "staff@dupemail.store";
        String userJson = """
                {
                    "email": "%s",
                    "password": "staffpassword",
                    "role": "MERCHANT_STAFF"
                }
                """.formatted(email);

        mockMvc.perform(post("/api/tenants/" + slug + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants/" + slug + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("User email already exists within tenant: " + email));
    }

    @Test
    void shouldReturn400OnInvalidBody() throws Exception {
        String invalidJson = """
                {
                    "slug": "",
                    "name": "Invalid Store",
                    "ownerEmail": "not-an-email",
                    "ownerPassword": "short"
                }
                """;

        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
