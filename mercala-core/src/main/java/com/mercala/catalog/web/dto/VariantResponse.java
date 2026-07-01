package com.mercala.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only DTO for product variant details returned in responses.
 */
public record VariantResponse(
    UUID id,
    String sku,
    BigDecimal price,
    Map<String, Object> attrs,
    String stockRef,
    Instant createdAt,
    Instant updatedAt
) {}
