package com.mercala.platform.security;

import java.util.UUID;

import com.mercala.identity.Role;

/**
 * The authenticated principal extracted from a validated JWT and stored in the
 * {@code SecurityContext}. Carries the tenant id so downstream tenant-scoping
 * (Hibernate filter + RLS in HAL-128/129) can read it.
 *
 * <p>{@code tenantId} is null for a user who signed up but hasn't created their
 * store yet (HAL-552) — authenticated, but with nothing tenant-scoped to see.
 */
public record AuthenticatedUser(UUID userId, UUID tenantId, String email, Role role) {
}
