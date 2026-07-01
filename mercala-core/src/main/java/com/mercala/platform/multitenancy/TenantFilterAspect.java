package com.mercala.platform.multitenancy;

import java.util.UUID;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Aspect that automatically intercepts repository calls and enables the Hibernate
 * {@code tenantFilter} on the active {@code Session} using the tenant ID from
 * {@link TenantContext}. This guarantees database queries are automatically scoped
 * at the application layer.
 */
@Aspect
@Component
public class TenantFilterAspect implements Ordered {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("execution(* org.springframework.data.repository.Repository+.*(..))")
    public void enableTenantFilter() {
        UUID tenantId = TenantContext.getCurrentTenant();
        Session session = entityManager.unwrap(Session.class);
        if (tenantId != null) {
            var filter = session.enableFilter("tenantFilter");
            filter.setParameter("tenantId", tenantId);
        } else {
            session.disableFilter("tenantFilter");
        }

        // Set the transaction-scoped tenant context parameter in PostgreSQL for RLS
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                stmt.setString(1, tenantId != null ? tenantId.toString() : "");
                stmt.execute();
            }
        });
    }

    @Override
    public int getOrder() {
        // Run with lowest precedence (after transaction advice starts)
        return Ordered.LOWEST_PRECEDENCE;
    }
}
