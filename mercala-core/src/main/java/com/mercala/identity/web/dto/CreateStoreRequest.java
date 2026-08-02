package com.mercala.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The dashboard onboarding step (HAL-552): a signed-in, storeless user names their
 * store. Unlike {@link CreateTenantRequest} there are no owner credentials here —
 * the owner is whoever is calling.
 */
public record CreateStoreRequest(
        @NotBlank @Size(max = 63) String slug,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description
) {}
