package com.mercala.identity.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update of the caller's own store profile. A null field means "leave it
 * unchanged" — the settings form only sends what it edits. An empty string is a
 * deliberate value: sending {@code description: ""} clears the blurb.
 */
public record UpdateTenantRequest(
        @Size(min = 1, max = 255) String name,
        @Size(max = 2000) String description
) {}
