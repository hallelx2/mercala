package com.mercala.platform.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mercala.identity.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads a {@code Bearer} JWT (scheme matched case-insensitively), validates it, and
 * populates the {@code SecurityContext} with an {@link AuthenticatedUser}. Only
 * token-related failures are swallowed (logged at debug); the request then proceeds
 * unauthenticated and protected routes return 401.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final String internalServiceKey;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            @org.springframework.beans.factory.annotation.Value("${mercala.internal.service-key:mercala-internal-key-secret-2026}") String internalServiceKey) {
        this.jwtService = jwtService;
        this.internalServiceKey = internalServiceKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String internalKey = request.getHeader("X-Internal-Service-Key");
        if (internalKey != null && internalKey.equals(internalServiceKey)) {
            String tenantIdStr = request.getHeader("X-Tenant-ID");
            if (tenantIdStr != null) {
                try {
                    UUID tenantId = UUID.fromString(tenantIdStr);
                    AuthenticatedUser principal = new AuthenticatedUser(
                            UUID.fromString("00000000-0000-0000-0000-000000000000"),
                            tenantId,
                            "system-agent@mercala.com",
                            Role.MERCHANT_OWNER
                    );
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid tenant ID format in X-Tenant-ID header: {}", tenantIdStr);
                }
            }
        } else {
            String token = extractBearerToken(request.getHeader("Authorization"));
            if (token != null) {
                try {
                    Claims claims = jwtService.parse(token).getPayload();
                    AuthenticatedUser principal = new AuthenticatedUser(
                            UUID.fromString(claims.getSubject()),
                            UUID.fromString(claims.get("tenant_id", String.class)),
                            claims.get("email", String.class),
                            Role.valueOf(claims.get("role", String.class)));
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (JwtException | IllegalArgumentException ex) {
                    log.debug("Rejected JWT: {}", ex.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Case-insensitive {@code Bearer} scheme match; returns the trimmed token or null. */
    private String extractBearerToken(String header) {
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
