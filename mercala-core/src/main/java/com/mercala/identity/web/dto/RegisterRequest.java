package com.mercala.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-serve signup (HAL-552): just the person. Their store comes later, from the
 * dashboard, via {@code POST /api/tenants/me}.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 100) String password
) {}
