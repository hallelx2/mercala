package com.mercala.catalog.web.dto;

import com.mercala.catalog.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only DTO representing a complete Product details view with variants.
 *
 * @param images newest first, and empty rather than null when a product has none. Attached
 *               after the fact by {@code ProductImageDecorator} rather than by the product
 *               mapper: imagery lives in another module, and resolving it per product would
 *               cost one query per row of a catalogue page (HAL-589).
 */
public record ProductResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    ProductStatus status,
    BigDecimal price,
    CategoryResponse category,
    List<String> tags,
    List<VariantResponse> variants,
    Instant createdAt,
    Instant updatedAt,
    List<ProductImageView> images
) {

    public ProductResponse {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * The shape the catalogue itself produces — a product, before anyone has looked up what
     * it looks like.
     */
    public ProductResponse(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            ProductStatus status,
            BigDecimal price,
            CategoryResponse category,
            List<String> tags,
            List<VariantResponse> variants,
            Instant createdAt,
            Instant updatedAt) {
        this(id, tenantId, name, description, status, price, category, tags, variants,
                createdAt, updatedAt, List.of());
    }

    /**
     * The same product, with its pictures. Records are immutable, so attaching imagery at
     * the edge means rebuilding — which is cheap, and keeps the mapper that knows about
     * catalogue rows free of anything that knows about object storage.
     */
    public ProductResponse withImages(List<ProductImageView> attached) {
        return new ProductResponse(
                id, tenantId, name, description, status, price,
                category, tags, variants, createdAt, updatedAt, attached);
    }
}
