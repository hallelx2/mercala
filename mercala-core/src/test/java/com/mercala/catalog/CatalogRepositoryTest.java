package com.mercala.catalog;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogRepositoryTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenants;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private VariantRepository variants;

    @Test
    void savesAndFindsProductWithVariantsAndCategory() {
        Tenant tenant = tenants.save(new Tenant("catalog-store", "Catalog Store"));

        // 1. Save Categories (Parent/Child relation)
        Category parent = categories.save(new Category(tenant.getId(), "Electronics", "electronics"));
        Category child = categories.save(new Category(tenant.getId(), "Phones", "phones", parent));

        // 2. Save Product with Variants
        Product product = new Product(tenant.getId(), "iPhone 15", "Apple smartphone", new BigDecimal("999.00"), child);
        product.setStatus(ProductStatus.ACTIVE);

        Variant v1 = new Variant("IPHONE-15-BLACK-128", Map.of("color", "Black", "storage", "128GB"), new BigDecimal("999.00"), "stock-ref-1");
        Variant v2 = new Variant("IPHONE-15-BLUE-256", Map.of("color", "Blue", "storage", "256GB"), new BigDecimal("1099.00"), "stock-ref-2");

        product.addVariant(v1);
        product.addVariant(v2);

        Product saved = products.save(product);

        // 3. Assertions
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCategory().getParent().getName()).isEqualTo("Electronics");
        assertThat(saved.getVariants()).hasSize(2);

        Variant firstVariant = saved.getVariants().stream()
                .filter(v -> v.getSku().equals("IPHONE-15-BLACK-128"))
                .findFirst().orElseThrow();
        assertThat(firstVariant.getAttrs().get("color")).isEqualTo("Black");
        assertThat(firstVariant.getPrice()).isEqualByComparingTo("999.00");
    }

    @Test
    void enforcesUniqueSlugPerTenantOnCategory() {
        Tenant tenant1 = tenants.save(new Tenant("store-slug-1", "Store Slug 1"));
        Tenant tenant2 = tenants.save(new Tenant("store-slug-2", "Store Slug 2"));

        categories.save(new Category(tenant1.getId(), "Shoes", "shoes"));

        // Duplicate slug for the same tenant must fail
        assertThatThrownBy(() ->
                categories.saveAndFlush(new Category(tenant1.getId(), "Footwear", "shoes")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Same slug for a different tenant must succeed
        Category otherTenantCategory = categories.saveAndFlush(new Category(tenant2.getId(), "Shoes", "shoes"));
        assertThat(otherTenantCategory.getId()).isNotNull();
    }

    @Test
    void enforcesUniqueSkuOnVariant() {
        Tenant tenant = tenants.save(new Tenant("store-sku", "Store SKU"));
        Category category = categories.save(new Category(tenant.getId(), "Apparel", "apparel"));

        Product p1 = products.save(new Product(tenant.getId(), "Shirt A", "Desc", new BigDecimal("29.99"), category));
        Product p2 = products.save(new Product(tenant.getId(), "Shirt B", "Desc", new BigDecimal("34.99"), category));

        p1.addVariant(new Variant("DUPLICATE-SKU", Map.of(), new BigDecimal("29.99"), "ref-1"));
        products.saveAndFlush(p1);

        p2.addVariant(new Variant("DUPLICATE-SKU", Map.of(), new BigDecimal("34.99"), "ref-2"));

        assertThatThrownBy(() -> products.saveAndFlush(p2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verifiesTenantIsolationOnCatalogEntities() throws Exception {
        Tenant tenantA = tenants.save(new Tenant("tenant-a-catalog", "Tenant A Catalog"));
        Tenant tenantB = tenants.save(new Tenant("tenant-b-catalog", "Tenant B Catalog"));

        Category catA = categories.save(new Category(tenantA.getId(), "A Category", "cat-a"));
        Category catB = categories.save(new Category(tenantB.getId(), "B Category", "cat-b"));

        Product pA = products.save(new Product(tenantA.getId(), "Product A", "Desc", new BigDecimal("10.00"), catA));
        pA.addVariant(new Variant("SKU-A", Map.of(), new BigDecimal("10.00"), "ref-a"));
        products.save(pA);

        Product pB = products.save(new Product(tenantB.getId(), "Product B", "Desc", new BigDecimal("20.00"), catB));
        pB.addVariant(new Variant("SKU-B", Map.of(), new BigDecimal("20.00"), "ref-b"));
        products.save(pB);

        // 1. Direct database verification using the restricted 'mercala_app' role via JDBC
        String jdbcUrl = POSTGRES.getJdbcUrl();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, "mercala_app", "mercala_app")) {
            conn.setAutoCommit(false);

            // Set app.current_tenant to Tenant A
            try (var stmt = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                stmt.setString(1, tenantA.getId().toString());
                stmt.execute();
            }

            // Verify Product RLS
            try (var stmt = conn.prepareStatement("SELECT name FROM product")) {
                try (var rs = stmt.executeQuery()) {
                    java.util.Set<String> names = new java.util.HashSet<>();
                    while (rs.next()) {
                        names.add(rs.getString("name"));
                    }
                    assertThat(names).contains("Product A").doesNotContain("Product B");
                }
            }

            // Verify Category RLS
            try (var stmt = conn.prepareStatement("SELECT name FROM category")) {
                try (var rs = stmt.executeQuery()) {
                    java.util.Set<String> names = new java.util.HashSet<>();
                    while (rs.next()) {
                        names.add(rs.getString("name"));
                    }
                    assertThat(names).contains("A Category").doesNotContain("B Category");
                }
            }

            // Verify Variant RLS (child entity check)
            try (var stmt = conn.prepareStatement("SELECT sku FROM variant")) {
                try (var rs = stmt.executeQuery()) {
                    java.util.Set<String> skus = new java.util.HashSet<>();
                    while (rs.next()) {
                        skus.add(rs.getString("sku"));
                    }
                    assertThat(skus).contains("SKU-A").doesNotContain("SKU-B");
                }
            }

            conn.rollback();
        }
    }
}
