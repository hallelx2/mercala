package com.mercala.inventory.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.catalog.Category;
import com.mercala.catalog.CategoryRepository;
import com.mercala.catalog.Product;
import com.mercala.catalog.ProductRepository;
import com.mercala.catalog.Variant;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.inventory.StockItem;
import com.mercala.inventory.StockItemRepository;
import com.mercala.platform.security.JwtService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InventoryControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;


    private String tokenForRole(Role role, Tenant tenant) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), "user-" + unique + "@example.test",
                passwordEncoder.encode("password"), role));
        return "Bearer " + jwtService.issue(user);
    }

    private Tenant createTenant(String slug) {
        return tenantRepository.save(new Tenant(slug, slug + " Inc."));
    }

    private Variant createTestVariant(Tenant tenant) {
        Category category = categoryRepository.save(new Category(tenant.getId(), "Test Category", "test-category-" + UUID.randomUUID()));
        Product product = new Product(tenant.getId(), "Test Product", "Description", new java.math.BigDecimal("99.99"), category);
        Variant variant = new Variant("TEST-SKU-" + UUID.randomUUID(), java.util.Map.of("color", "Red"), new java.math.BigDecimal("99.99"), "ref-1");
        product.addVariant(variant);
        productRepository.save(product);
        return variant;
    }

    @Test
    void managesStockLifecycleThroughController() throws Exception {
        Tenant tenant = createTenant("inventory-flow");
        String ownerToken = tokenForRole(Role.MERCHANT_OWNER, tenant);
        String shopperToken = tokenForRole(Role.SHOPPER, tenant);

        Variant variant = createTestVariant(tenant);

        // 1. Initial lookup fails since no StockItem exists yet
        mockMvc.perform(get("/api/inventory/" + variant.getId())
                        .header("Authorization", shopperToken))
                .andExpect(status().isNotFound());

        // 2. Adjust stock as merchant owner (creates StockItem)
        String adjustJson = """
                {"quantity":100}
                """;
        mockMvc.perform(post("/api/inventory/" + variant.getId() + "/adjust")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variantId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.reservedQuantity").value(0))
                .andExpect(jsonPath("$.availableQuantity").value(100));

        // 3. Lookup stock as shopper succeeds now
        mockMvc.perform(get("/api/inventory/" + variant.getId())
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.availableQuantity").value(100));

        // 4. Shopper cannot adjust stock (Forbidden)
        mockMvc.perform(post("/api/inventory/" + variant.getId() + "/adjust")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustJson))
                .andExpect(status().isForbidden());

        // 5. Adjust stock with invalid negative quantity must fail with 400 Bad Request
        String invalidAdjustJson = """
                {"quantity":-5}
                """;
        mockMvc.perform(post("/api/inventory/" + variant.getId() + "/adjust")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidAdjustJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enforcesTenantIsolationOnLookupAndAdjustment() throws Exception {
        Tenant tenantA = createTenant("tenant-a-inventory");
        Tenant tenantB = createTenant("tenant-b-inventory");

        String ownerTokenA = tokenForRole(Role.MERCHANT_OWNER, tenantA);
        String ownerTokenB = tokenForRole(Role.MERCHANT_OWNER, tenantB);

        Variant variantA = createTestVariant(tenantA);

        // 1. Create StockItem for Tenant A's variant using Tenant A's owner
        String adjustJson = """
                {"quantity":50}
                """;
        mockMvc.perform(post("/api/inventory/" + variantA.getId() + "/adjust")
                        .header("Authorization", ownerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustJson))
                .andExpect(status().isOk());

        // 2. Tenant B's owner attempts to lookup Tenant A's variant stock -> should be 404 (isolated!)
        mockMvc.perform(get("/api/inventory/" + variantA.getId())
                        .header("Authorization", ownerTokenB))
                .andExpect(status().isNotFound());

        // 3. Tenant B's owner attempts to adjust Tenant A's variant stock -> should be 404 (isolated!)
        mockMvc.perform(post("/api/inventory/" + variantA.getId() + "/adjust")
                        .header("Authorization", ownerTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustJson))
                .andExpect(status().isNotFound());
    }
}
