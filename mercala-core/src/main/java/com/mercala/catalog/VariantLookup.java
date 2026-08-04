package com.mercala.catalog;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.platform.multitenancy.TenantContext;

/**
 * Turns variant ids into something a person can read.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Carts and orders store a variant id and a quantity, which is right — a line references
 * the thing bought, it does not copy it. But every surface that shows a line to a human
 * needs the product's name and the variant's SKU, and resolving those one line at a time is
 * a query per row on a page that already knows all its ids.
 *
 * <p>So the lookup is batched and lives in the catalogue, which is the module that owns the
 * answer. Cart and order assemble their own responses from it rather than reaching into
 * catalogue entities themselves.
 *
 * <h2>Tenancy</h2>
 *
 * <p>The query names the tenant explicitly rather than relying on the Hibernate filter, for
 * the same reason {@code ProductImageDecorator} does: the filter is installed per
 * transaction by an aspect, and a response assembler may or may not run inside one. An id
 * from another tenant simply is not in the returned map, so a caller cannot accidentally
 * render it.
 */
@Component
public class VariantLookup {

    /** What a line needs to be legible: what it is, which product, and what it costs. */
    public record VariantSummary(
            UUID variantId,
            String sku,
            UUID productId,
            String productName,
            BigDecimal price) {}

    private final VariantRepository variants;

    public VariantLookup(VariantRepository variants) {
        this.variants = variants;
    }

    /**
     * @return summaries keyed by variant id, omitting ids that do not belong to the current
     *         tenant or no longer exist
     */
    @Transactional(readOnly = true)
    public Map<UUID, VariantSummary> byIds(Collection<UUID> variantIds) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || variantIds.isEmpty()) {
            return Map.of();
        }

        return variants.findAllByIdInAndTenantId(variantIds, tenantId).stream()
                .map(variant -> new VariantSummary(
                        variant.getId(),
                        variant.getSku(),
                        variant.getProduct().getId(),
                        variant.getProduct().getName(),
                        variant.getPrice()))
                .collect(Collectors.toMap(VariantSummary::variantId, Function.identity()));
    }
}
