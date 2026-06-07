package com.mercala.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String tenantSlug,
        @NotBlank @Email String email,
        @NotBlank String password
) {}
