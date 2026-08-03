package com.mercala.catalog.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.media.MediaObjectStorage;
import com.mercala.media.ProductImage;
import com.mercala.media.ProductImageRepository;
import com.mercala.platform.multitenancy.TenantContext;
import com.mercala.platform.security.JwtService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The anonymous storefront, exercised anonymously — no Authorization header anywhere in
 * these requests. That absence is the point: every case here proves a shopper with no
 * account sees exactly what they should and nothing they shouldn't.
 */
@AutoConfigureMockMvc
class PublicStoreControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestRestTemplate rest;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private ProductImageRepository productImages;

    // No object storage in a test run, and signing is not what these assert.
    @MockBean private MediaObjectStorage storage;

    private static final String STORED_URL = "http://storage/tenant/product.png";
    private static final String SIGNED_URL = "http://storage/tenant/product.png?X-Amz-Signature=abc";

    @org.junit.jupiter.api.BeforeEach
    void stubSigning() {
        org.mockito.Mockito.when(storage.objectKeyOf(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("tenant/product.png");
        org.mockito.Mockito.when(storage.presignedView(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenReturn(SIGNED_URL);
    }

    /** The image worker writes outside a request, so the tenant is installed the same way. */
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

    private Tenant createTenant(String slug, String description) {
        Tenant tenant = new Tenant(slug, slug + " Shop");
        tenant.setDescription(description);
        return tenantRepository.save(tenant);
    }

    private String ownerToken(Tenant tenant) {
        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
                passwordEncoder.encode("password"), Role.MERCHANT_OWNER));
        return "Bearer " + jwtService.issue(user);
    }

    /** Creates a product through the real API and returns its id. */
    private String createProduct(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/products")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "desc", "price": 10.00}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    @Test
    void profileIsPublicAndCarriesTheDescription() throws Exception {
        createTenant("open-shop", "Linen, made to order.");

        mockMvc.perform(get("/api/public/stores/open-shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("open-shop"))
                .andExpect(jsonPath("$.name").value("open-shop Shop"))
                .andExpect(jsonPath("$.description").value("Linen, made to order."));
    }

    /**
     * The bug this class exists for: a missing store must be a 404, not a 401. Without
     * {@code /error} in the permitAll list, Boot's error dispatch re-entered the filter
     * chain anonymously and the 404 left the building as "Authentication required".
     *
     * <p>Deliberately NOT MockMvc: MockMvc never performs the servlet container's ERROR
     * dispatch to {@code /error}, so a MockMvc version of this test passes with or
     * without the fix (review finding on PR #65). {@code TestRestTemplate} against the
     * RANDOM_PORT container runs the real dispatch — removing {@code /error} from the
     * permitAll list makes this fail with 401, which is exactly the regression it guards.
     */
    @Test
    void missingStoreIsNotFoundNotUnauthorized() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/public/stores/no-such-store", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void productListIsScopedToTheStoreInThePath() throws Exception {
        Tenant shopA = createTenant("shop-a", null);
        Tenant shopB = createTenant("shop-b", null);
        createProduct(ownerToken(shopA), "A's Chair");
        createProduct(ownerToken(shopB), "B's Table");

        mockMvc.perform(get("/api/public/stores/shop-a/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("A's Chair"));
    }

    @Test
    void archivedProductVanishesFromTheListAndItsIdReads404() throws Exception {
        Tenant tenant = createTenant("quiet-shop", null);
        String token = ownerToken(tenant);
        String productId = createProduct(token, "Soon Gone");

        // Visible while ACTIVE…
        mockMvc.perform(get("/api/public/stores/quiet-shop/products/" + productId))
                .andExpect(status().isOk());

        // …archive it through the real API…
        mockMvc.perform(put("/api/products/" + productId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Soon Gone", "description": "desc", "price": 10.00, "status": "ARCHIVED"}
                                """))
                .andExpect(status().isOk());

        // …and it is indistinguishable from never having existed.
        mockMvc.perform(get("/api/public/stores/quiet-shop/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/public/stores/quiet-shop/products/" + productId))
                .andExpect(status().isNotFound());
    }

    /**
     * The point of HAL-589, asserted anonymously: a shopper with no session gets a URL that
     * loads. The stored one is a 403 from a browser — the bucket is private — so the signed
     * one is what makes a storefront look like a shop.
     */
    @Test
    void aShopperSeesProductImagesWithoutAnyCredentials() throws Exception {
        Tenant tenant = createTenant("linen-shop", null);
        String token = ownerToken(tenant);
        String productId = createProduct(token, "Linen shirt");
        attachImage(tenant.getId(), UUID.fromString(productId), STORED_URL);

        mockMvc.perform(get("/api/public/stores/linen-shop/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].images.length()").value(1))
                .andExpect(jsonPath("$.content[0].images[0].url").value(STORED_URL))
                .andExpect(jsonPath("$.content[0].images[0].viewUrl").value(SIGNED_URL));

        mockMvc.perform(get("/api/public/stores/linen-shop/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].viewUrl").value(SIGNED_URL));
    }

    @Test
    void aProductWithNoImageryListsAnEmptyArrayRatherThanNull() throws Exception {
        Tenant tenant = createTenant("bare-shop", null);
        createProduct(ownerToken(tenant), "Linen shirt");

        mockMvc.perform(get("/api/public/stores/bare-shop/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].images").isArray())
                .andExpect(jsonPath("$.content[0].images.length()").value(0));
    }

    /** Imagery must not become the field through which one store sees another's. */
    @Test
    void oneStoresImageryNeverAppearsOnAnothersStorefront() throws Exception {
        Tenant mine = createTenant("mine-shop", null);
        Tenant theirs = createTenant("theirs-shop", null);
        String myProduct = createProduct(ownerToken(mine), "Mine");
        String theirProduct = createProduct(ownerToken(theirs), "Theirs");
        attachImage(theirs.getId(), UUID.fromString(theirProduct), STORED_URL);

        mockMvc.perform(get("/api/public/stores/mine-shop/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Mine"))
                .andExpect(jsonPath("$.content[0].images.length()").value(0));

        // And the id of my own product does not pick up their picture either.
        mockMvc.perform(get("/api/public/stores/mine-shop/products/" + myProduct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(0));
    }
}
