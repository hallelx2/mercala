package com.mercala.platform.security;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless JWT security with method-level RBAC.
 *
 * <p>{@code @EnableMethodSecurity} turns on {@code @PreAuthorize} so individual methods can
 * gate by role. URL-level rules keep public: health, API docs, login, and tenant signup
 * ({@code POST /api/tenants}). Adding users to a tenant requires authentication here and is
 * further restricted to owners by {@code @PreAuthorize} on the controller. Unauthenticated →
 * 401; authenticated but wrong role → 403.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            com.mercala.platform.multitenancy.TenantContextFilter tenantContextFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The Boot error dispatch forwards failed requests to /error. Without
                        // this, an anonymous request's 404 re-enters the filter chain, fails
                        // auth there, and leaves as a 401 — a missing public store claimed
                        // "Authentication required" instead of "not found".
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/docs", "/api/v1/docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll() // self-serve signup (HAL-552)
                        .requestMatchers(HttpMethod.POST, "/api/tenants").permitAll()   // public store signup
                        // The storefront: anonymous shoppers browsing a store. GET-only on
                        // purpose — nothing under /api/public may ever mutate.
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        .requestMatchers(org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher("/api/webhooks/**")).permitAll()                // public webhooks
                        // /api/media is deliberately absent from this list. It used to be
                        // permitAll on a wildcard, which made POST /api/media/replay —
                        // a cross-tenant Kafka replay trigger — callable by anyone on the
                        // internet, and would have silently published every future endpoint
                        // added under that prefix (HAL-495). It now falls through to
                        // anyRequest().authenticated() like the rest of the API, with the
                        // replay endpoint further restricted to PLATFORM_ADMIN at the method.
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->                     // 401 (not logged in)
                                writeProblem(res, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required"))
                        .accessDeniedHandler((req, res, ex) ->                          // 403 (wrong role)
                                writeProblem(res, HttpStatus.FORBIDDEN, "Forbidden", "Insufficient permissions")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantContextFilter, JwtAuthenticationFilter.class)
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());
        return http.build();
    }

    /** Writes a minimal RFC 7807 {@code application/problem+json} body — shared by the 401 + 403 handlers. */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}".formatted(title, status.value(), detail));
    }
}
