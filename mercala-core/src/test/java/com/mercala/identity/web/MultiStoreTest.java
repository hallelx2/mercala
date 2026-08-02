package com.mercala.identity.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mercala.AbstractIntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One account, many stores (HAL-556). The property that matters most here is the last
 * one: each store's catalogue is disjoint, and switching stores switches worlds —
 * a product created while store B is active must never surface under store A.
 */
@AutoConfigureMockMvc
class MultiStoreTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerAndGetToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Multi Owner", "email": "%s", "password": "a-long-password-1"}
                                """.formatted(unique("multi") + "@example.test")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private String createStore(String token, String slug, String name) throws Exception {
        String body = mockMvc.perform(post("/api/tenants/me")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug": "%s", "name": "%s"}
                                """.formatted(slug, name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private void createProduct(String token, String name) throws Exception {
        mockMvc.perform(post("/api/products")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "d", "price": 10.00}
                                """.formatted(name)))
                .andExpect(status().isCreated());
    }

    @Test
    void eachStoreHasItsOwnWorldAndSwitchingSwapsThem() throws Exception {
        String token = registerAndGetToken();
        String slugA = unique("store-a");
        String slugB = unique("store-b");

        String tokenA = createStore(token, slugA, "Store A");
        createProduct(tokenA, "Only In A");

        String tokenB = createStore(tokenA, slugB, "Store B");
        createProduct(tokenB, "Only In B");

        // Store B's session sees only B's product.
        mockMvc.perform(get("/api/products").header("Authorization", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Only In B"));

        // Switch back to A: the reissued token sees only A's product.
        String switched = mockMvc.perform(post("/api/auth/switch-store")
                        .header("Authorization", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug": "%s"}
                                """.formatted(slugA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenBackToA = "Bearer " + JsonPath.read(switched, "$.accessToken");

        mockMvc.perform(get("/api/products").header("Authorization", tokenBackToA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Only In A"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", tokenBackToA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSlug").value(slugA))
                .andExpect(jsonPath("$.stores.length()").value(2));
    }

    @Test
    void switchingToAStoreYouDoNotBelongToIs404() throws Exception {
        String owner = registerAndGetToken();
        String outsider = registerAndGetToken();
        String slug = unique("private-store");
        createStore(owner, slug, "Private Store");

        // A real store, but not the outsider's — same 404 as a fictional slug, so the
        // endpoint can't be used to probe which stores exist.
        mockMvc.perform(post("/api/auth/switch-store")
                        .header("Authorization", outsider)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug": "%s"}
                                """.formatted(slug)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/switch-store")
                        .header("Authorization", outsider)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug": "no-such-store-anywhere"}
                                """))
                .andExpect(status().isNotFound());
    }
}
