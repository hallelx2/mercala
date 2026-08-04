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

    /**
     * @param question the preamble — why this is being asked, in one sentence
     * @param fields   everything needed, asked at once. A model that can only ask one thing
     *                 per call will write the rest as prose instead, and prose is not a
     *                 control the merchant can fill in
     * @param options  the older single-question shape: choices for {@code question} itself.
     *                 Kept because models reach for the simpler schema, and normalised into
     *                 {@code fields} server-side so the client sees one shape
     */
    public record AskUserArgs(
            String question,
            List<AskFieldArg> fields,
            List<String> options,
            Boolean allowFreeText
    ) {}

    /**
     * One thing being asked for.
     *
     * @param name        the key the answer comes back under
     * @param label       what the merchant reads
     * @param type        {@code text}, {@code textarea}, {@code number}, {@code money},
     *                    {@code choice} or {@code image}
     * @param options     the choices, for {@code choice}. The merchant may always answer
     *                    with something not on the list
     * @param placeholder an example, not a default — nothing is pre-filled from it
     * @param optional    true when the answer can be skipped. Default is required, because a
     *                    model asking for something it does not need is the cheaper mistake
     */
    public record AskFieldArg(
            String name,
            String label,
            String type,
            List<String> options,
            String placeholder,
            Boolean optional
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
