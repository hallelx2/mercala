package com.mercala.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        /**
         * Optional since HAL-552. Without it the password disambiguates among the
         * accounts sharing the email; the slug is only needed when two accounts
         * share both email and password.
         */
        String tenantSlug,
        @NotBlank @Email String email,
        @NotBlank String password
) {}
