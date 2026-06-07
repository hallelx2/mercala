package com.mercala.identity.web.dto;

import java.util.UUID;

import com.mercala.identity.Role;

public record MeResponse(UUID userId, UUID tenantId, String email, Role role) {}
