package com.mercala.order.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.cart.CartService;
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
import com.mercala.inventory.InventoryService;
import com.mercala.platform.multitenancy.TenantContext;
import com.mercala.platform.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CheckoutControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CartService cartService;
    @Autowired private InventoryService inventoryService;

    private AppUser createUser(Role role, Tenant tenant) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new AppUser(
                tenant.getId(), "user-" + unique + "@example.test",
                passwordEncoder.encode("password"), role));
    }

    private Tenant createTenant(String slug) {
        return tenantRepository.save(new Tenant(slug, slug + " Inc."));
    }

    private Variant createTestVariantWithStock(Tenant tenant, int initialStock) {
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            Category category = categoryRepository.save(new Category(tenant.getId(), "Test Category", "test-category-" + UUID.randomUUID()));
            Product product = new Product(tenant.getId(), "Test Product", "Description", new java.math.BigDecimal("99.99"), category);
            Variant variant = new Variant("TEST-SKU-" + UUID.randomUUID(), java.util.Map.of("color", "Red"), new java.math.BigDecimal("99.99"), "ref-1");
            product.addVariant(variant);
            productRepository.save(product);

            // Adjust stock level in database
            inventoryService.adjustStock(variant.getId(), initialStock);

            return variant;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void checkoutLifecycleAndIdempotencyFlow() throws Exception {
        Tenant tenant = createTenant("checkout-flow");
        AppUser shopper = createUser(Role.SHOPPER, tenant);
        String shopperToken = "Bearer " + jwtService.issue(shopper);

        // 1. Attempt checkout with empty cart must fail
        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // 2. Add item to cart
        Variant variant = createTestVariantWithStock(tenant, 10);
        
        // Add to shopper's cart
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            cartService.addOrUpdateLine(shopper.getId(), variant.getId(), 2);
        } finally {
            TenantContext.clear();
        }

        // 3. Perform checkout with idempotency key
        String idempotencyKey = UUID.randomUUID().toString();
        String checkoutJson = String.format("""
                {"idempotencyKey":"%s"}
                """, idempotencyKey);

        String orderResponseStr = mockMvc.perform(post("/api/checkout")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(199.98))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey))
                .andExpect(jsonPath("$.lines[0].variantId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(99.99))
                // The client that just placed this should not have to re-fetch to learn
                // when it happened (HAL-575).
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(orderResponseStr, "$.id"));

        // Reading it back carries the same timestamp, asserted as equality rather than as
        // presence: three code paths build this response, and three different-but-present
        // timestamps would satisfy an existence check while making the dashboard's chart
        // disagree with its own order list.
        String placedAt = com.jayway.jsonpath.JsonPath.read(orderResponseStr, "$.createdAt");

        mockMvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(placedAt));

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].createdAt").value(placedAt));

        // 4. Cart should now be empty after successful checkout
        mockMvc.perform(get("/api/cart")
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isEmpty());

        // 5. Stock item should have reserved quantity equal to 2 (available is now 8)
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            var stockItem = inventoryService.getStockItem(variant.getId());
            assertThat(stockItem.getQuantity()).isEqualTo(10);
            assertThat(stockItem.getReservedQuantity()).isEqualTo(2);
            assertThat(stockItem.getAvailableQuantity()).isEqualTo(8);
        } finally {
            TenantContext.clear();
        }

        // 6. Resubmit duplicate checkout with the same idempotency key -> returns the same order (no new order/reservation)
        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.idempotencyKey").value(idempotencyKey));

        // 7. Verify stock levels did not change after duplicate checkout
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            var stockItem = inventoryService.getStockItem(variant.getId());
            assertThat(stockItem.getReservedQuantity()).isEqualTo(2);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void preventsCheckoutOnInsufficientStock() throws Exception {
        Tenant tenant = createTenant("checkout-fail");
        AppUser shopper = createUser(Role.SHOPPER, tenant);
        String shopperToken = "Bearer " + jwtService.issue(shopper);

        // Create item with only 1 stock
        Variant variant = createTestVariantWithStock(tenant, 1);

        // Add 2 items to cart (oversell attempt)
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            cartService.addOrUpdateLine(shopper.getId(), variant.getId(), 2);
        } finally {
            TenantContext.clear();
        }

        // Checkout should fail
        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"fail-key"}
                                """))
                .andExpect(status().isBadRequest());

        // Verify stock levels and cart are unmodified
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            var stockItem = inventoryService.getStockItem(variant.getId());
            assertThat(stockItem.getReservedQuantity()).isEqualTo(0);
            
            var cart = cartService.getOrCreateCart(shopper.getId());
            assertThat(cart.getLines()).hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enforcesTenantIsolationOnIdempotentCheckout() throws Exception {
        Tenant tenantA = createTenant("checkout-tenant-a");
        Tenant tenantB = createTenant("checkout-tenant-b");

        AppUser shopperA = createUser(Role.SHOPPER, tenantA);
        AppUser shopperB = createUser(Role.SHOPPER, tenantB);

        String tokenA = "Bearer " + jwtService.issue(shopperA);
        String tokenB = "Bearer " + jwtService.issue(shopperB);

        Variant variantA = createTestVariantWithStock(tenantA, 5);
        Variant variantB = createTestVariantWithStock(tenantB, 5);

        // 1. Setup Cart A & checkout with key "shared-idempotency-key"
        TenantContext.setCurrentTenant(tenantA.getId());
        try {
            cartService.addOrUpdateLine(shopperA.getId(), variantA.getId(), 1);
        } finally {
            TenantContext.clear();
        }

        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"shared-idempotency-key"}
                                """))
                .andExpect(status().isOk());

        // 2. Setup Cart B & checkout with the same key "shared-idempotency-key" -> should succeed and create a NEW separate order because tenant RLS keeps them isolated!
        TenantContext.setCurrentTenant(tenantB.getId());
        try {
            cartService.addOrUpdateLine(shopperB.getId(), variantB.getId(), 1);
        } finally {
            TenantContext.clear();
        }

        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"shared-idempotency-key"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].variantId").value(variantB.getId().toString()));
    }
}
