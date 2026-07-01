package com.mercala.catalog.web;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
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
class ProductControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

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
    void productLifeCycleFullFlow() throws Exception {
        Tenant tenant = createTenant("flow-store");
        String ownerToken = tokenForRole(Role.MERCHANT_OWNER, tenant);
        String shopperToken = tokenForRole(Role.SHOPPER, tenant);

        // 1. Create Category
        String categoryJson = """
                {"name":"Books","slug":"books"}
                """;

        String categoryIdStr = mockMvc.perform(post("/api/categories")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Books"))
                .andExpect(jsonPath("$.slug").value("books"))
                .andReturn().getResponse().getContentAsString();

        UUID categoryId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(categoryIdStr, "$.id"));

        // 2. Create Product (with nested variants and category)
        String productJson = """
                {
                    "name": "Java Book",
                    "description": "Learn Java programming",
                    "price": 49.99,
                    "categoryId": "%s",
                    "variants": [
                        {
                            "sku": "JAVA-BOOK-PAPER",
                            "price": 49.99,
                            "attrs": {"format": "Paperback"},
                            "stockRef": "stock-paper"
                        }
                    ]
                }
                """.formatted(categoryId);

        String productResponseStr = mockMvc.perform(post("/api/products")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Java Book"))
                .andExpect(jsonPath("$.category.id").value(categoryId.toString()))
                .andExpect(jsonPath("$.variants[0].sku").value("JAVA-BOOK-PAPER"))
                .andExpect(jsonPath("$.variants[0].attrs.format").value("Paperback"))
                .andReturn().getResponse().getContentAsString();

        UUID productId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(productResponseStr, "$.id"));
        UUID variantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(productResponseStr, "$.variants[0].id"));

        // 3. Shopper Read Single Product
        mockMvc.perform(get("/api/products/" + productId)
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Java Book"));

        // 4. Shopper List Products
        mockMvc.perform(get("/api/products")
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Java Book"));

        // 5. Update Product (Owner)
        String updateProductJson = """
                {
                    "name": "Java Programming",
                    "description": "Advanced Java",
                    "price": 59.99,
                    "categoryId": "%s",
                    "status": "ACTIVE"
                }
                """.formatted(categoryId);

        mockMvc.perform(put("/api/products/" + productId)
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateProductJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Java Programming"))
                .andExpect(jsonPath("$.price").value(59.99));

        // 6. Add Variant (Staff)
        String staffToken = tokenForRole(Role.MERCHANT_STAFF, tenant);
        String addVariantJson = """
                {
                    "sku": "JAVA-BOOK-PDF",
                    "price": 39.99,
                    "attrs": {"format": "PDF"},
                    "stockRef": "stock-pdf"
                }
                """;

        String variantResponseStr = mockMvc.perform(post("/api/products/" + productId + "/variants")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addVariantJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("JAVA-BOOK-PDF"))
                .andExpect(jsonPath("$.price").value(39.99))
                .andReturn().getResponse().getContentAsString();

        UUID pdfVariantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(variantResponseStr, "$.id"));

        // 7. Update Variant (Staff)
        String updateVariantJson = """
                {
                    "sku": "JAVA-BOOK-PDF-V2",
                    "price": 44.99,
                    "attrs": {"format": "PDF v2"},
                    "stockRef": "stock-pdf-v2"
                }
                """;

        mockMvc.perform(put("/api/products/" + productId + "/variants/" + pdfVariantId)
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateVariantJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("JAVA-BOOK-PDF-V2"))
                .andExpect(jsonPath("$.price").value(44.99));

        // 8. Delete Variant (Owner)
        mockMvc.perform(delete("/api/products/" + productId + "/variants/" + variantId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNoContent());

        // Verify variant deleted
        mockMvc.perform(get("/api/products/" + productId)
                        .header("Authorization", shopperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].sku").value("JAVA-BOOK-PDF-V2"));

        // 9. Delete Product (Owner)
        mockMvc.perform(delete("/api/products/" + productId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNoContent());

        // Verify product deleted
        mockMvc.perform(get("/api/products/" + productId)
                        .header("Authorization", shopperToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shopperCannotWriteToCatalog() throws Exception {
        Tenant tenant = createTenant("shopper-test-store");
        String shopperToken = tokenForRole(Role.SHOPPER, tenant);

        String productJson = """
                {
                    "name": "Java Book",
                    "description": "Learn Java programming",
                    "price": 49.99
                }
                """;

        mockMvc.perform(post("/api/products")
                        .header("Authorization", shopperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidProductPayloads() throws Exception {
        Tenant tenant = createTenant("invalid-test-store");
        String ownerToken = tokenForRole(Role.MERCHANT_OWNER, tenant);

        // Missing name and negative price
        String invalidJson = """
                {
                    "name": "",
                    "description": "Short",
                    "price": -10.00
                }
                """;

        mockMvc.perform(post("/api/products")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists());
    }
}
