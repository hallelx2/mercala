package com.mercala.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank @Size(max = 63) String slug,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 320) String ownerEmail,
        @NotBlank @Size(min = 8, max = 100) String ownerPassword
) {}
