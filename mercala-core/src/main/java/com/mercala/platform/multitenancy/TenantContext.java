package com.mercala.platform.multitenancy;

import java.util.UUID;

/**
 * Request-scoped container for the current tenant ID. Uses a {@code ThreadLocal}
 * to ensure the tenant context is isolated to the current request thread and does
 * not leak across concurrent requests.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // utility class
    }

    public static void setCurrentTenant(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
