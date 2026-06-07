package com.mercala.identity.web.dto;

import java.util.UUID;

public record TenantResponse(
        UUID id,
        String slug,
        String name,
        String status
) {}
