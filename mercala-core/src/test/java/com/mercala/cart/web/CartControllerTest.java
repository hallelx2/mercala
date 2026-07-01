package com.mercala.cart.web;

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
import com.mercala.platform.security.JwtService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CartControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;

    private AppUser createUser(Role role, Tenant tenant) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new AppUser(
                tenant.getId(), "user-" + unique + "@example.test",
                passwordEncoder.encode("password"), role));
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
    void managesCartLifecycleThroughController() throws Exception {
        Tenant tenant = createTenant("cart-flow");
        AppUser shopper = createUser(Role.SHOPPER, tenant);
        String shopperToken = "Bearer " + jwtService.issue(shopper);

        Variant variant = createTestVariant(tenant);

        // 1. Initial cart is empty
        mockMvc.perform(get("/api/cart")
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(shopper.getId().toString()))
                .andExpect(jsonPath("$.lines").isEmpty());

        // 2. Add item to cart
        String addJson = String.format("""
                {"variantId":"%s","quantity":2}
                """, variant.getId());
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].variantId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));

        // 3. Add same item increments quantity
        String addMoreJson = String.format("""
                {"variantId":"%s","quantity":3}
                """, variant.getId());
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addMoreJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(5));

        // 4. Update item quantity
        String updateJson = """
                {"quantity":1}
                """;
        mockMvc.perform(put("/api/cart/items/" + variant.getId())
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(1));

        // 5. Remove item from cart
        mockMvc.perform(delete("/api/cart/items/" + variant.getId())
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isEmpty());

        // 6. Add back and clear cart
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addJson))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/cart")
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cart")
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isEmpty());
    }

    @Test
    void enforcesTenantIsolationOnCarts() throws Exception {
        Tenant tenantA = createTenant("tenant-a-cart");
        Tenant tenantB = createTenant("tenant-b-cart");

        AppUser shopperA = createUser(Role.SHOPPER, tenantA);
        AppUser shopperB = createUser(Role.SHOPPER, tenantB);

        String tokenA = "Bearer " + jwtService.issue(shopperA);
        String tokenB = "Bearer " + jwtService.issue(shopperB);

        Variant variantA = createTestVariant(tenantA);

        // 1. Shopper B attempts to add Tenant A's variant to their cart -> should be 404 (not accessible/isolated)
        String addJson = String.format("""
                {"variantId":"%s","quantity":1}
                """, variantA.getId());
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addJson))
                .andExpect(status().isNotFound());

        // 2. Shopper A successfully adds Variant A to Cart A
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addJson))
                .andExpect(status().isOk());

        // 3. Shopper B tries to update Shopper A's cart items -> should be 404 (not found in Shopper B's cart)
        mockMvc.perform(put("/api/cart/items/" + variantA.getId())
                        .header("Authorization", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":10}
                                """))
                .andExpect(status().isNotFound());
    }
}
