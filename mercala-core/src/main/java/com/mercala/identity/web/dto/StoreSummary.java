package com.mercala.identity.web.dto;

import java.util.UUID;

import com.mercala.identity.Role;

/** One row of "your stores" — enough for a switcher, no more. */
public record StoreSummary(UUID tenantId, String slug, String name, Role role) {}
