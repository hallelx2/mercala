package com.mercala.identity.web;

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
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RegistrationControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    // --- helpers -------------------------------------------------------------

    private String tenantJson(String slug, String name, String ownerEmail) {
        return """
                {"slug":"%s","name":"%s","ownerEmail":"%s","ownerPassword":"supersecretpassword"}
                """.formatted(slug, name, ownerEmail);
    }

    private String userJson(String email, Role role) {
        return """
                {"email":"%s","password":"staffpassword","role":"%s"}
                """.formatted(email, role.name());
    }

    /** Mint a Bearer token for the (already-created) owner of a tenant. */
    private String ownerToken(String slug, String ownerEmail) {
        Tenant tenant = tenantRepository.findBySlug(slug).orElseThrow();
        AppUser owner = userRepository.findByTenantIdAndEmail(tenant.getId(), ownerEmail).orElseThrow();
        return "Bearer " + jwtService.issue(owner);
    }

    /** Seed a user with the given role (in its own tenant) and mint a Bearer token. */
    private String tokenForRole(Role role) {
        String slug = "tok-" + role.name().toLowerCase();
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseGet(() -> tenantRepository.save(new Tenant(slug, slug)));
        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), role.name().toLowerCase() + "@" + slug + ".test",
                passwordEncoder.encode("supersecretpassword"), role));
        return "Bearer " + jwtService.issue(user);
    }

    // --- tenant signup (public) ---------------------------------------------

    @Test
    void shouldCreateTenantAndOwner() throws Exception {
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("test-store", "Test Store", "owner@test.store")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("test-store"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Tenant tenant = tenantRepository.findBySlug("test-store").orElseThrow();
        AppUser owner = userRepository.findByTenantIdAndEmail(tenant.getId(), "owner@test.store").orElseThrow();
        assertThat(owner.getRole()).isEqualTo(Role.MERCHANT_OWNER);
        assertThat(passwordEncoder.matches("supersecretpassword", owner.getPasswordHash())).isTrue();
    }

    @Test
    void shouldReturn409OnDuplicateTenantSlug() throws Exception {
        String json = tenantJson("dup-store-test", "Dup Store", "owner@dup.store");
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldReturn400OnInvalidBody() throws Exception {
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"","name":"X","ownerEmail":"not-an-email","ownerPassword":"short"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // --- add user (owner-only RBAC) -----------------------------------------

    @Test
    void ownerCanAddUserToTenant() throws Exception {
        String slug = "user-store";
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(tenantJson(slug, "User Store", "owner@user.store"))).andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants/" + slug + "/users")
                        .header("Authorization", ownerToken(slug, "owner@user.store"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("staff@user.store", Role.MERCHANT_STAFF)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("staff@user.store"))
                .andExpect(jsonPath("$.role").value("MERCHANT_STAFF"));
    }

    @Test
    void addUserWithoutTokenReturns401() throws Exception {
        String slug = "noauth-store";
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(tenantJson(slug, "NoAuth Store", "owner@noauth.store"))).andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants/" + slug + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("staff@noauth.store", Role.MERCHANT_STAFF)))
                .andExpect(status().isUnauthorized());          // not logged in
    }

    @Test
    void addUserAsShopperReturns403() throws Exception {
        String slug = "rbac-store";
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(tenantJson(slug, "Rbac Store", "owner@rbac.store"))).andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants/" + slug + "/users")
                        .header("Authorization", tokenForRole(Role.SHOPPER))   // logged in, but wrong role
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("staff@rbac.store", Role.MERCHANT_STAFF)))
                .andExpect(status().isForbidden());             // 403
    }

    @Test
    void addUserToUnknownTenantReturns404() throws Exception {
        mockMvc.perform(post("/api/tenants/unknown-store/users")
                        .header("Authorization", tokenForRole(Role.MERCHANT_OWNER))   // an owner, valid role
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("staff@unknown.store", Role.MERCHANT_STAFF)))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateEmailWithinTenantReturns409() throws Exception {
        String slug = "dup-email-store";
        mockMvc.perform(post("/api/tenants").contentType(MediaType.APPLICATION_JSON)
                .content(tenantJson(slug, "Dup Email Store", "owner@dupemail.store"))).andExpect(status().isCreated());

        String token = ownerToken(slug, "owner@dupemail.store");
        String user = userJson("staff@dupemail.store", Role.MERCHANT_STAFF);

        mockMvc.perform(post("/api/tenants/" + slug + "/users").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(user)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/tenants/" + slug + "/users").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(user)).andExpect(status().isConflict());
    }
}
