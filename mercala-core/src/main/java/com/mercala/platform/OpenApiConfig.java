package com.mercala.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI document metadata and security. springdoc auto-derives the paths/schemas from
 * the controllers + DTOs; this sets the title/version shown in the Scalar reference at
 * {@code /api/v1/docs} and declares how the API is authenticated.
 *
 * <p>The security scheme is not cosmetic. Client generators derive their auth handling
 * entirely from {@code components.securitySchemes} — without it a generated SDK emits
 * unauthenticated requests and the header has to be bolted on by hand, which defeats
 * generating from the contract at all. It also gives the Scalar UI its authorize control,
 * so endpoints can be exercised without hand-crafting a header.
 *
 * <p>The requirement is applied globally and opted out of per-endpoint, because the
 * authenticated set is far larger than the public one. Public endpoints carry
 * {@code @SecurityRequirements} (empty) and must match the {@code permitAll} rules in
 * {@link com.mercala.platform.security.SecurityConfig} — if the two disagree, the
 * published document lies about the API.
 */
@Configuration
public class OpenApiConfig {

    /** Referenced by name wherever a scheme has to be named explicitly. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI mercalaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mercala API")
                        .version("v0.1.0")
                        .description("""
                                Agent-native, multi-tenant e-commerce platform — REST API.

                                Authenticate with POST /api/auth/login to obtain a JWT, then send it as \
                                `Authorization: Bearer <token>` on every other endpoint. Tenant scoping is \
                                derived from the token, never from a request parameter."""))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT issued by POST /api/auth/login.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
