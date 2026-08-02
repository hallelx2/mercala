package com.mercala.media;

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
import com.mercala.platform.multitenancy.TenantContext;
import com.mercala.platform.security.JwtService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading imagery back. The interesting cases are both about scope: a merchant sees their
 * own product's images, and cannot see another store's by guessing an id.
 */
@AutoConfigureMockMvc
class ProductImageControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private ProductImageRepository productImages;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private record Store(Tenant tenant, String token) {}

    private Store store(String slug) {
        Tenant tenant = tenantRepository.save(new Tenant(slug + "-" + UUID.randomUUID().toString().substring(0, 6), slug));
        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), "owner-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("password"), Role.MERCHANT_OWNER));
        return new Store(tenant, "Bearer " + jwtService.issue(user));
    }

    private UUID createProduct(String token) throws Exception {
        String body = mockMvc.perform(post("/api/products")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Linen shirt", "description": "navy", "price": 49.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    /** The consumer writes images outside a request, so the tenant is set the same way here. */
    private void attachImage(UUID tenantId, UUID productId, String url) {
        UUID previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            productImages.save(new ProductImage(tenantId, productId, url));
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    @Test
    void aProductsImagesComeBackNewestFirst() throws Exception {
        Store shop = store("linen");
        UUID productId = createProduct(shop.token());
        attachImage(shop.tenant().getId(), productId, "http://storage/first.png");
        attachImage(shop.tenant().getId(), productId, "http://storage/second.png");

        mockMvc.perform(get("/api/products/" + productId + "/images")
                        .header("Authorization", shop.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].url").value("http://storage/second.png"));
    }

    @Test
    void aProductWithNoImagesIsAnEmptyListRatherThanAnError() throws Exception {
        Store shop = store("linen");

        mockMvc.perform(get("/api/products/" + createProduct(shop.token()) + "/images")
                        .header("Authorization", shop.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Guessing another store's product id must not surface their photography. */
    @Test
    void anotherStoresImagesAreNotVisible() throws Exception {
        Store mine = store("mine");
        Store theirs = store("theirs");
        UUID theirProduct = createProduct(theirs.token());
        attachImage(theirs.tenant().getId(), theirProduct, "http://storage/theirs.png");

        mockMvc.perform(get("/api/products/" + theirProduct + "/images")
                        .header("Authorization", mine.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anAnonymousRequestIsRefused() throws Exception {
        mockMvc.perform(get("/api/products/" + UUID.randomUUID() + "/images"))
                .andExpect(status().isUnauthorized());
    }
}
