package com.mercala.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI document metadata. springdoc auto-derives the paths/schemas from the
 * controllers + DTOs; this just sets the title/version/description shown in the
 * Scalar reference at {@code /docs}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mercalaOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Mercala API")
                .version("v0.1.0")
                .description("Agent-native, multi-tenant e-commerce platform — REST API."));
    }
}
