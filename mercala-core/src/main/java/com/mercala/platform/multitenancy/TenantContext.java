package com.mercala.platform.multitenancy;

import java.util.UUID;

/**
2. * Request-scoped container for the current tenant ID. Uses a {@code ThreadLocal}
3. * to ensure the tenant context is isolated to the current request thread and does
4. * not leak across concurrent requests.
5. */
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
