package com.mercala.catalog.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.catalog.service.ProductService;
import com.mercala.catalog.web.dto.CreateProductRequest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.multitenancy.TenantContext;
import com.mercala.platform.security.JwtService;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ProductSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private ProductService productService;

    // --- Helpers -------------------------------------------------------------

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

    // --- Tests ---------------------------------------------------------------

    @Test
    void verifiesLexicalSearchRankingAndTenantIsolation() throws Exception {
        // 1. Setup Tenant A and Tenant B
        Tenant tenantA = createTenant("search-store-a");
        Tenant tenantB = createTenant("search-store-b");

        String shopperAToken = tokenForRole(Role.SHOPPER, tenantA);
        String shopperBToken = tokenForRole(Role.SHOPPER, tenantB);

        // 2. Seed Tenant A products (manually bypass filter using TenantContext)
        TenantContext.setCurrentTenant(tenantA.getId());
        try {
            productService.createProduct(new CreateProductRequest(
                    "Red Running Shoes",
                    "Best shoes for marathon training",
                    new BigDecimal("120.00"),
                    null,
                    List.of("shoes", "running", "red"),
                    List.of()
            ));
            productService.createProduct(new CreateProductRequest(
                    "Blue Running Socks",
                    "Comfortable cotton socks",
                    new BigDecimal("15.00"),
                    null,
                    List.of("socks", "running", "blue"),
                    List.of()
            ));
            productService.createProduct(new CreateProductRequest(
                    "Coffee Mug",
                    "Ceramic mug for hot coffee",
                    new BigDecimal("10.00"),
                    null,
                    List.of("mug", "coffee", "kitchen"),
                    List.of()
            ));
        } finally {
            TenantContext.clear();
        }

        // 3. Seed Tenant B products
        TenantContext.setCurrentTenant(tenantB.getId());
        try {
            productService.createProduct(new CreateProductRequest(
                    "Tenant B Running Shoes",
                    "Exclusive runner shoes for B customers",
                    new BigDecimal("130.00"),
                    null,
                    List.of("shoes", "running"),
                    List.of()
            ));
        } finally {
            TenantContext.clear();
        }

        // 4. Test Search as Tenant A Shopper

        // Query: "shoes" -> Should match "Red Running Shoes" only, NOT Tenant B's shoes
        mockMvc.perform(get("/api/search")
                        .header("Authorization", shopperAToken)
                        .param("q", "shoes")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Red Running Shoes"));

        // Query: "Running" -> Should match both "Red Running Shoes" and "Blue Running Socks"
        mockMvc.perform(get("/api/search")
                        .header("Authorization", shopperAToken)
                        .param("q", "Running")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        // Query: "socks" -> Should match "Blue Running Socks"
        mockMvc.perform(get("/api/search")
                        .header("Authorization", shopperAToken)
                        .param("q", "socks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Blue Running Socks"));

        // 5. Test Search as Tenant B Shopper

        // Query: "shoes" -> Should match "Tenant B Running Shoes", NOT Tenant A's shoes
        mockMvc.perform(get("/api/search")
                        .header("Authorization", shopperBToken)
                        .param("q", "shoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Tenant B Running Shoes"));
    }
}
