package com.mercala.identity.web.dto;

import java.util.UUID;

import com.mercala.identity.Role;

/**
 * The authenticated caller, enriched with their store's public identity. The
 * slug matters to clients: it is the login scope and the storefront address,
 * and without it here a frontend cannot even link to its own store.
 */
public record MeResponse(
        UUID userId,
        UUID tenantId,
        String email,
        Role role,
        String tenantSlug,
        String tenantName,
        String tenantDescription
) {}
