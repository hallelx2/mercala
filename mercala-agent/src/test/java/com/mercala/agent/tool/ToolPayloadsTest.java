package com.mercala.agent.tool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercala.agent.tool.ToolPayloads.*;

/**
 * Unit tests for tool payload records and argument binding.
 * These tests validate that the record schemas are correctly structured
 * and that Spring AI will be able to deserialize arguments into them.
 */
class ToolPayloadsTest {

    // ── CreateProductArgs ──────────────────────────────────────────

    @Test
    void createProductArgs_bindsAllFields() {
        var variant = new VariantArg("SKU-NAVY-S", new BigDecimal("49.00"), Map.of("size", "S", "color", "Navy"));
        var args = new CreateProductArgs(
                "Linen Shirt",
                "A premium navy linen shirt",
                new BigDecimal("49.00"),
                "cat-123",
                List.of("clothing", "linen", "summer"),
                List.of(variant)
        );

        assertThat(args.name()).isEqualTo("Linen Shirt");
        assertThat(args.description()).isEqualTo("A premium navy linen shirt");
        assertThat(args.price()).isEqualByComparingTo("49.00");
        assertThat(args.categoryId()).isEqualTo("cat-123");
        assertThat(args.tags()).containsExactly("clothing", "linen", "summer");
        assertThat(args.variants()).hasSize(1);
        assertThat(args.variants().get(0).sku()).isEqualTo("SKU-NAVY-S");
        assertThat(args.variants().get(0).attrs()).containsEntry("size", "S");
    }

    @Test
    void createProductArgs_handlesNullOptionalFields() {
        var args = new CreateProductArgs("Widget", null, new BigDecimal("10.00"), null, null, null);

        assertThat(args.name()).isEqualTo("Widget");
        assertThat(args.description()).isNull();
        assertThat(args.categoryId()).isNull();
        assertThat(args.tags()).isNull();
        assertThat(args.variants()).isNull();
    }

    // ── SearchCatalogArgs ──────────────────────────────────────────

    @Test
    void searchCatalogArgs_defaultsMode() {
        var args = new SearchCatalogArgs("hiking boots", null, 0, 10);

        assertThat(args.query()).isEqualTo("hiking boots");
        assertThat(args.mode()).isEqualTo("hybrid");
        assertThat(args.page()).isZero();
        assertThat(args.size()).isEqualTo(10);
    }

    @Test
    void searchCatalogArgs_correctsNegativePageAndZeroSize() {
        var args = new SearchCatalogArgs("gift", "semantic", -1, 0);

        assertThat(args.page()).isZero();
        assertThat(args.size()).isEqualTo(10);
    }

    @Test
    void searchCatalogArgs_preservesExplicitMode() {
        var args = new SearchCatalogArgs("shirt", "lexical", 2, 20);

        assertThat(args.mode()).isEqualTo("lexical");
        assertThat(args.page()).isEqualTo(2);
        assertThat(args.size()).isEqualTo(20);
    }

    // ── UpdateInventoryArgs ────────────────────────────────────────

    @Test
    void updateInventoryArgs_bindsFields() {
        var args = new UpdateInventoryArgs("variant-abc-123", 50);

        assertThat(args.variantId()).isEqualTo("variant-abc-123");
        assertThat(args.quantity()).isEqualTo(50);
    }

    @Test
    void updateInventoryArgs_allowsNegativeForReduction() {
        var args = new UpdateInventoryArgs("variant-xyz", -10);
        assertThat(args.quantity()).isEqualTo(-10);
    }

    // ── GetProductArgs ─────────────────────────────────────────────

    @Test
    void getProductArgs_bindsProductId() {
        var args = new GetProductArgs("prod-12345");
        assertThat(args.productId()).isEqualTo("prod-12345");
    }

    // ── VariantArg ─────────────────────────────────────────────────

    @Test
    void variantArg_handlesNullAttrs() {
        var variant = new VariantArg("SKU-001", new BigDecimal("25.00"), null);

        assertThat(variant.sku()).isEqualTo("SKU-001");
        assertThat(variant.price()).isEqualByComparingTo("25.00");
        assertThat(variant.attrs()).isNull();
    }
}
