package com.mercala.platform.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mercala.AbstractIntegrationTest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the OpenAPI spec is generated, declares how it is authenticated, and stays
 * honest about which endpoints are public.
 */
@AutoConfigureMockMvc
class ApiDocsTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions apiDocs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void openApiSpecIsServedAndIncludesTenantEndpoints() throws Exception {
        apiDocs()
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
     * missing a generated SDK silently emits unauthenticated requests — surfacing as 401s at
     * runtime rather than as a build failure, which is exactly what makes it worth pinning.
     */
    @Test
    void declaresBearerAuthSecurityScheme() throws Exception {
        apiDocs()
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    /**
     * Asserts bearerAuth is present in the global requirement without pinning its position
     * or the size of the array — adding a second global scheme later is a legitimate change
     * and should not break this test.
     */
    @Test
    void appliesBearerAuthGloballyByDefault() throws Exception {
        apiDocs().andExpect(jsonPath("$.security..bearerAuth").exists());
    }

    /**
     * The document has to agree with SecurityConfig's permitAll rules. Documenting login as
     * requiring a token is self-contradictory — it is where tokens come from — and a
     * generated client would refuse to call it without one.
     *
     * <p>This list is maintained by hand alongside SecurityConfig. Deriving both from one
     * source would be better and is worth doing, but SecurityConfig mixes method-specific
     * matchers with ant patterns, so a shared constant would only cover part of it and give
     * false confidence about the rest.
     *
     * <p>{@code /api/media/replay} appears here because the API genuinely behaves this way
     * today, not because it should — see HAL-495. When that rule changes this case fails
     * until the document follows, which is the point.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/login",
            "/api/tenants",
            "/api/webhooks/stripe",
            "/api/webhooks/paystack",
            "/api/webhooks/flutterwave",
            "/api/media/replay",
    })
    void publicEndpointsOptOutOfTheGlobalSecurityRequirement(String path) throws Exception {
        apiDocs().andExpect(jsonPath("$.paths['" + path + "'].post.security", hasSize(0)));
    }

    /**
     * A protected endpoint must NOT carry an empty security array. That is worse than no
     * annotation at all: it tells a client no token is needed and produces 401s the client
     * has no way to explain.
     */
    @Test
    void protectedEndpointsInheritTheGlobalRequirement() throws Exception {
        apiDocs()
                .andExpect(jsonPath("$.paths['/api/products'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/cart'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/orders'].get.security").doesNotExist());
    }
}
