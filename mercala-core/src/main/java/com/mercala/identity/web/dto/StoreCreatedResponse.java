package com.mercala.identity.web.dto;

import java.util.UUID;

/**
 * The store plus a fresh session. The caller's old JWT has no tenant claim; every
 * tenant-scoped call would still see nothing. Returning the reissued token here makes
 * "create store" one atomic step from the client's point of view instead of
 * create-then-relogin.
 */
public record StoreCreatedResponse(
        UUID id,
        String slug,
        String name,
        String status,
        String description,
        String accessToken,
        String tokenType,
        long expiresIn
) {}
