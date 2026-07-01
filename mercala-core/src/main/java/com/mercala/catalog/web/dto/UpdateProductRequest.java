package com.mercala.catalog.web.dto;

import com.mercala.catalog.ProductStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for updating an existing product.
 */
public record UpdateProductRequest(
    @NotBlank(message = "Product name is required")
    @Size(max = 255, message = "Product name cannot exceed 255 characters")
    String name,

    String description,

    @NotNull(message = "Product price is required")
    @PositiveOrZero(message = "Product price must be zero or positive")
    BigDecimal price,

    UUID categoryId,

    @NotNull(message = "Product status is required")
    ProductStatus status
) {}
