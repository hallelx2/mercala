package com.mercala.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI document metadata for the Agent microservice.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agentOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Mercala Agent API")
                .version("v0.1.0")
                .description("AI Agent service for merchant and shopper flows — REST API."));
    }
}
