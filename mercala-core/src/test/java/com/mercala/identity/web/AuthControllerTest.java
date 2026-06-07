package com.mercala.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void seedOwner(String slug, String email, String rawPassword) {
        Tenant tenant = tenantRepository.save(new Tenant(slug, slug));
        userRepository.save(new AppUser(tenant.getId(), email, passwordEncoder.encode(rawPassword), Role.MERCHANT_OWNER));
    }

    private String body(String slug, String email, String password) {
        return """
                {"tenantSlug":"%s","email":"%s","password":"%s"}
                """.formatted(slug, email, password);
    }

    @Test
    void loginWithValidCredentialsReturnsToken() throws Exception {
        seedOwner("auth-a", "owner@auth-a.test", "supersecret1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("auth-a", "owner@auth-a.test", "supersecret1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        seedOwner("auth-b", "owner@auth-b.test", "supersecret1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("auth-b", "owner@auth-b.test", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithUnknownTenantReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("no-such-tenant", "x@y.test", "supersecret1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidTokenReturnsPrincipal() throws Exception {
        seedOwner("auth-c", "owner@auth-c.test", "supersecret1");

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("auth-c", "owner@auth-c.test", "supersecret1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.accessToken");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@auth-c.test"))
                .andExpect(jsonPath("$.role").value("MERCHANT_OWNER"))
                .andExpect(jsonPath("$.tenantId").exists());
    }
}
