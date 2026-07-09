package com.mercala.agent.security;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.client.MercalaCoreClient;
import com.mercala.agent.web.AgentSecurityFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentSecurityFilterTest {

    private MercalaCoreClient coreClient;
    private Environment env;
    private AgentSecurityFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        coreClient = mock(MercalaCoreClient.class);
        env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});

        filter = new AgentSecurityFilter(coreClient, env);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        responseWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(responseWriter);
        when(response.getWriter()).thenReturn(printWriter);
    }

    @AfterEach
    void tearDown() {
        try { AgentContext.clear(); } catch (Exception ignored) {}
    }

    @Test
    void doFilter_bypassesNonAgentPaths() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/public/endpoint");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(coreClient);
    }

    @Test
    void doFilter_bypassesInTestProfile() throws Exception {
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        // Re-init filter to pick up test profile
        filter = new AgentSecurityFilter(coreClient, env);

        when(request.getRequestURI()).thenReturn("/api/agent/merchant/chat");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(coreClient);
    }

    @Test
    void doFilter_rejectsMissingAuthorizationHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/agent/merchant/chat");
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/problem+json");
        assertThat(responseWriter.toString()).contains("Authentication required");
        verifyNoInteractions(chain);
    }

    @Test
    void doFilter_authenticatesValidToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(request.getRequestURI()).thenReturn("/api/agent/merchant/chat");
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(coreClient.getAuthMe("Bearer valid-token")).thenReturn(Map.of(
                "userId", userId.toString(),
                "tenantId", tenantId.toString(),
                "role", "MERCHANT_OWNER"
        ));

        doAnswer(invocation -> {
            AgentContext ctx = AgentContext.current();
            assertThat(ctx.userId()).isEqualTo(userId);
            assertThat(ctx.tenantId()).isEqualTo(tenantId);
            assertThat(ctx.userRole()).isEqualTo("MERCHANT_OWNER");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        // Context should be cleared after filter completion
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, AgentContext::current);
    }

    @Test
    void doFilter_rejectsInvalidToken() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/agent/merchant/chat");
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
        when(coreClient.getAuthMe("Bearer invalid-token")).thenThrow(new RuntimeException("Token expired"));

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/problem+json");
        assertThat(responseWriter.toString()).contains("Authentication failed");
        verifyNoInteractions(chain);
    }
}
