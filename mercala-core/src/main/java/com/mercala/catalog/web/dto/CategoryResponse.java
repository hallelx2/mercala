package com.mercala.catalog.web.dto;

import java.util.UUID;

/**
 * Read-only DTO for Category details returned in the catalog API responses.
 */
public record CategoryResponse(
    UUID id,
    String name,
    String slug
) {}
