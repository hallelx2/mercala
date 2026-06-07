package com.mercala.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank @Size(max = 63) String slug,
        @NotBlank String name,
        @NotBlank @Email String ownerEmail,
        @NotBlank @Size(min = 8) String ownerPassword
) {}
