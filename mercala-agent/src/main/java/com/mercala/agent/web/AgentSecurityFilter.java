package com.mercala.agent.web;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.client.MercalaCoreClient;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Filter that authenticates requests to the agent chat endpoints by invoking the core service's auth verification.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AgentSecurityFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AgentSecurityFilter.class);
    private final MercalaCoreClient coreClient;
    private final boolean isTestProfile;

    public AgentSecurityFilter(MercalaCoreClient coreClient, org.springframework.core.env.Environment env) {
        this.coreClient = coreClient;
        this.isTestProfile = env != null && java.util.Arrays.asList(env.getActiveProfiles()).contains("test");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            String path = httpRequest.getRequestURI();
            if (path.startsWith("/api/agent/merchant/") || path.startsWith("/api/agent/shopper/")) {
                if (isTestProfile) {
                    chain.doFilter(request, response);
                    return;
                }

                String authHeader = httpRequest.getHeader("Authorization");
                if (authHeader == null || authHeader.isBlank()) {
                    log.warn("Missing Authorization header for agent request: {}", path);
                    writeUnauthorized(httpResponse, "Authentication required");
                    return;
                }

                try {
                    Map<String, Object> me = coreClient.getAuthMe(authHeader);
                    if (me == null || me.containsKey("error")) {
                        log.warn("AuthMe request failed for token to path: {}", path);
                        writeUnauthorized(httpResponse, "Invalid session");
                        return;
                    }

                    // Extract user details
                    UUID userId = UUID.fromString((String) me.get("userId"));
                    UUID tenantId = UUID.fromString((String) me.get("tenantId"));
                    String role = (String) me.get("role");

                    // Set thread-local context for agent processing
                    AgentContext.set(new AgentContext(tenantId, userId, role));
                    try {
                        chain.doFilter(request, response);
                    } finally {
                        AgentContext.clear();
                    }
                } catch (Exception e) {
                    log.error("Exception authenticating agent request for path {}: {}", path, e.getMessage());
                    writeUnauthorized(httpResponse, "Authentication failed: " + e.getMessage());
                }
            } else {
                chain.doFilter(request, response);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\"}".formatted(detail));
    }
}
