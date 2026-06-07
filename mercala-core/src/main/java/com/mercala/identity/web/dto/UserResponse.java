package com.mercala.identity.web.dto;

import java.util.UUID;

import com.mercala.identity.Role;

public record UserResponse(
        UUID id,
        String email,
        Role role
) {}
