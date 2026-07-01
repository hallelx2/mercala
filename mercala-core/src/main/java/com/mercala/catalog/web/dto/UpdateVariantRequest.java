package com.mercala.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Request payload for updating an existing variant.
 */
public record UpdateVariantRequest(
    @NotBlank(message = "Variant SKU is required")
    @Size(max = 100, message = "Variant SKU cannot exceed 100 characters")
    String sku,

    @NotNull(message = "Variant price is required")
    @PositiveOrZero(message = "Variant price must be zero or positive")
    BigDecimal price,

    Map<String, Object> attrs,

    String stockRef
) {}
