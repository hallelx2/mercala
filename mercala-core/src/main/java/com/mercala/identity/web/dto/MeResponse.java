package com.mercala.identity.web.dto;

import java.util.UUID;

import com.mercala.identity.Role;

/**
 * The authenticated caller, enriched with their store's public identity. The
 * slug matters to clients: it is the login scope and the storefront address,
 * and without it here a frontend cannot even link to its own store.
 *
 * <p>All tenant fields are null for a user who hasn't created their store yet
 * (HAL-552) — the dashboard uses exactly that to show onboarding instead.
 */
public record MeResponse(
        UUID userId,
        UUID tenantId,
        String email,
        Role role,
        String name,
        String tenantSlug,
        String tenantName,
        String tenantDescription,
        /** Every store the caller belongs to (HAL-556) — the switcher's data. */
        java.util.List<StoreSummary> stores
) {}
