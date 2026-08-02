package com.mercala.identity.web;

import java.util.UUID;

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

/**
 * The identity-first journey (HAL-552): sign up as a person, land in the dashboard with
 * no store, create the store from there, and only then act as a merchant. Each step uses
 * the token the previous step returned — the test fails if any handoff (signup → session,
 * store creation → reissued tenant token) breaks.
 */
@AutoConfigureMockMvc
class SelfServeSignupTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String register(String name, String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "email": "%s", "password": "%s"}
                                """.formatted(name, email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    @Test
    void signUpCreateStoreThenActAsMerchant() throws Exception {
        String email = unique("ada") + "@example.test";
        String token = register("Ada Person", email, "a-long-password-1");

        // Signed in, but no store yet — exactly what the dashboard's onboarding keys on.
        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ada Person"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.tenantSlug").doesNotExist());

        // Create the store from inside the session; the response reissues the token.
        String slug = unique("adas-atelier");
        String storeBody = mockMvc.perform(post("/api/tenants/me")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug": "%s", "name": "Ada's Atelier", "description": "Prints and paper goods."}
                                """.formatted(slug)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn().getResponse().getContentAsString();
        String tenantToken = "Bearer " + JsonPath.read(storeBody, "$.accessToken");

        // The fresh token carries the tenant…
        mockMvc.perform(get("/api/auth/me").header("Authorization", tenantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSlug").value(slug))
                .andExpect(jsonPath("$.tenantDescription").value("Prints and paper goods."));

        // …and real merchant work succeeds with it.
        mockMvc.perform(post("/api/products")
                        .header("Authorization", tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Letterpress Print", "description": "A3", "price": 30.00}
                                """))
                .andExpect(status().isCreated());

        // A second store on the same account is a conflict, not a silent replacement.
        mockMvc.perform(post("/api/tenants/me")
                        .header("Authorization", tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug": "%s-two", "name": "Second Store"}
                                """.formatted(slug)))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateEmailCannotRegisterTwice() throws Exception {
        String email = unique("dup") + "@example.test";
        register("First", email, "a-long-password-1");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Second", "email": "%s", "password": "another-password-2"}
                                """.formatted(email)))
                .andExpect(status().isConflict());
    }

    @Test
    void sluglessLoginFindsTheAccountByPassword() throws Exception {
        String email = unique("solo") + "@example.test";
        register("Solo Owner", email, "a-long-password-1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "a-long-password-1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        // Wrong password stays indistinguishable from a nonexistent account.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong-password-x"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Legacy shape: the same email exists in two stores as two accounts. If the
     * passwords differ, the password picks the account. If both match, the API must
     * ask for the slug rather than guess which store the caller meant.
     */
    @Test
    void sluglessLoginDisambiguatesByPasswordAndConflictsWhenItCannot() throws Exception {
        String email = unique("shared") + "@example.test";
        Tenant shopOne = tenantRepository.save(new Tenant(unique("shop-one"), "Shop One"));
        Tenant shopTwo = tenantRepository.save(new Tenant(unique("shop-two"), "Shop Two"));
        userRepository.save(new AppUser(shopOne.getId(), email,
                passwordEncoder.encode("password-for-one"), Role.MERCHANT_OWNER));
        userRepository.save(new AppUser(shopTwo.getId(), email,
                passwordEncoder.encode("password-for-two"), Role.MERCHANT_OWNER));

        // Distinct passwords: the password disambiguates.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password-for-one"}
                                """.formatted(email)))
                .andExpect(status().isOk());

        // Same password in a third store: now genuinely ambiguous → 409 asking for the slug.
        Tenant shopThree = tenantRepository.save(new Tenant(unique("shop-three"), "Shop Three"));
        userRepository.save(new AppUser(shopThree.getId(), email,
                passwordEncoder.encode("password-for-one"), Role.MERCHANT_OWNER));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password-for-one"}
                                """.formatted(email)))
                .andExpect(status().isConflict());

        // And the slug resolves it, exactly as before HAL-552.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSlug": "%s", "email": "%s", "password": "password-for-one"}
                                """.formatted(shopOne.getSlug(), email)))
                .andExpect(status().isOk());
    }
}
