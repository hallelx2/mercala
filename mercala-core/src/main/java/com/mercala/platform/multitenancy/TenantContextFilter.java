package com.mercala.platform.multitenancy;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mercala.platform.security.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter that extracts the tenant ID from the authenticated user principal (if present)
 * and propagates it to {@link TenantContext} for the duration of the request.
 * Cleans up the context in {@code finally} to prevent thread pool leaks.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.getPrincipal() instanceof AuthenticatedUser user
                    && user.tenantId() != null) {
                // A null tenant is a user who hasn't created their store yet (HAL-552);
                // leaving the context empty keeps tenant-scoped queries returning nothing
                // rather than poisoning the ThreadLocal with a null.
                TenantContext.setCurrentTenant(user.tenantId());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
