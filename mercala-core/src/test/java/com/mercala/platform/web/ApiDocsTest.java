package com.mercala.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the OpenAPI spec is generated, declares how it is authenticated, and is honest
 * about which endpoints are public.
 */
@AutoConfigureMockMvc
class ApiDocsTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiSpecIsServedAndIncludesTenantEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/tenants")))
                .andExpect(content().string(containsString("/api/products")))
                .andExpect(content().string(containsString("/api/categories")))
                .andExpect(content().string(containsString("/api/search")));
    }

    @Test
    void scalarReferencePageIsServed() throws Exception {
        mockMvc.perform(get("/api/v1/docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@scalar/api-reference")));
    }

    /**
     * Client generators derive their auth handling entirely from this block. If it goes
     * missing, a generated SDK silently emits unauthenticated requests — surfacing as 401s
     * at runtime rather than as a build failure, which is exactly the kind of thing worth
     * pinning in a test.
     */
    @Test
    void declaresBearerAuthSecurityScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void appliesBearerAuthGloballyByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security", hasSize(1)))
                .andExpect(jsonPath("$.security[0].bearerAuth").exists());
    }

    /**
     * The document has to agree with SecurityConfig's permitAll rules. Documenting login as
     * requiring a token is self-contradictory — it is where tokens come from — and a
     * generated client would refuse to call it without one.
     */
    @Test
    void loginOptsOutOfTheGlobalSecurityRequirement() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security", hasSize(0)));
    }

    @Test
    void tenantSignupOptsOutOfTheGlobalSecurityRequirement() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/tenants'].post.security", hasSize(0)));
    }

    /**
     * Payment providers call these with no Mercala token; authenticity comes from
     * per-provider signature verification, not from the Authorization header.
     */
    @Test
    void webhooksOptOutOfTheGlobalSecurityRequirement() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/webhooks/stripe'].post.security", hasSize(0)))
                .andExpect(jsonPath("$.paths['/api/webhooks/paystack'].post.security", hasSize(0)))
                .andExpect(jsonPath("$.paths['/api/webhooks/flutterwave'].post.security", hasSize(0)));
    }

    /**
     * A protected endpoint must NOT carry an empty security array — that would tell a client
     * no token is needed and produce 401s it has no way to explain.
     */
    @Test
    void protectedEndpointsInheritTheGlobalRequirement() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/products'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/cart'].get.security").doesNotExist());
    }
}
