package com.mercala.agent.tool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Record payloads that mirror the mercala-core DTOs,
 * used as Spring AI tool-function arguments.
 */
public final class ToolPayloads {

    private ToolPayloads() {}

    // ── CreateProduct ──────────────────────────────────────────────

    public record CreateProductArgs(
            String name,
            String description,
            BigDecimal price,
            String categoryId,
            List<String> tags,
            List<VariantArg> variants
    ) {}

    public record VariantArg(
            String sku,
            BigDecimal price,
            Map<String, Object> attrs
    ) {}

    // ── SearchCatalog ──────────────────────────────────────────────

    public record SearchCatalogArgs(
            String query,
            String mode,
            int page,
            int size
    ) {
        public SearchCatalogArgs {
            if (mode == null || mode.isBlank()) mode = "hybrid";
            if (page < 0) page = 0;
            if (size <= 0) size = 10;
        }
    }

    // ── UpdateInventory ────────────────────────────────────────────

    public record UpdateInventoryArgs(
            String variantId,
            int quantity
    ) {}

    // ── GetProduct ─────────────────────────────────────────────────

    public record GetProductArgs(
            String productId
    ) {}
}
