package com.mercala.catalog.web.dto;

import com.mercala.catalog.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only DTO representing a complete Product details view with variants.
 */
public record ProductResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    ProductStatus status,
    BigDecimal price,
    CategoryResponse category,
    List<VariantResponse> variants,
    Instant createdAt,
    Instant updatedAt
) {}
