package com.mercala.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Request payload for creating a category.
 */
public record CreateCategoryRequest(
    @NotBlank(message = "Category name is required")
    String name,

    @NotBlank(message = "Category slug is required")
    String slug,

    UUID parentId
) {}
