package com.mercala.catalog.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request payload for creating a product with optional variants.
 */
public record CreateProductRequest(
    @NotBlank(message = "Product name is required")
    @Size(max = 255, message = "Product name cannot exceed 255 characters")
    String name,

    String description,

    @NotNull(message = "Product price is required")
    @PositiveOrZero(message = "Product price must be zero or positive")
    BigDecimal price,

    UUID categoryId,

    List<String> tags,

    @Valid
    List<CreateVariantRequest> variants
) {}
