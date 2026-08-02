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

    // ── RequestProductImage ────────────────────────────────────────

    public record RequestProductImageArgs(
            String productId,
            String prompt
    ) {}

    // ── EnhanceProductImage ────────────────────────────────────────

    /**
     * @param sourceImageUrl the merchant's own photo, as returned by the media upload endpoint
     * @param instruction    what to change — "remove the background, studio lighting"
     * @param strength       0.0 keeps the original, 1.0 ignores it; the useful range is 0.2–0.6
     */
    public record EnhanceProductImageArgs(
            String productId,
            String sourceImageUrl,
            String instruction,
            Double strength
    ) {}

    // ── Human-in-the-loop ──────────────────────────────────────────

    public record AskUserArgs(
            String question,
            List<String> options,
            Boolean allowFreeText
    ) {}

    public record ConfirmActionArgs(
            String action,
            String summary,
            String details,
            String importance
    ) {}

    public record ProposeEditArgs(
            String entityType,
            String entityId,
            String summary,
            List<EditFieldArg> fields
    ) {}

    /**
     * @param name  the field's key in whatever will eventually be applied
     * @param label what the merchant reads
     * @param type  {@code text}, {@code textarea}, {@code number}, {@code money}, {@code tags}
     * @param value the agent's proposal, which the merchant may overwrite
     */
    public record EditFieldArg(
            String name,
            String label,
            String type,
            String value
    ) {}
}
