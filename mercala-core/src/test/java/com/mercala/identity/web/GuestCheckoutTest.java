package com.mercala.identity.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.inventory.InventoryService;
import com.mercala.platform.multitenancy.TenantContext;
import com.mercala.platform.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A shopper who has signed up for nothing, buying something.
 *
 * <p>The design being tested is that a guest gets a real tenant-scoped identity rather than
 * a parallel anonymous path through cart and checkout — so the interesting assertions are
 * not "does it work" but "does the isolation that protects a merchant's data also hold for
 * an identity nobody authenticated".
 */
@AutoConfigureMockMvc
class GuestCheckoutTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private InventoryService inventoryService;

    /**
     * A store with a slug nobody else can be holding. The suite shares one database across
     * classes, so a readable fixed slug is a collision waiting for the day another test
     * picks the same word — which is exactly how this first failed.
     */
    private Tenant store(String name) {
        return tenantRepository.save(
                new Tenant(name + "-" + UUID.randomUUID(), name + " Shop"));
    }

    private String ownerToken(Tenant tenant) {
        AppUser owner = userRepository.save(new AppUser(
                tenant.getId(), "owner-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("password"), Role.MERCHANT_OWNER));
        return "Bearer " + jwtService.issue(owner);
    }

    /** A guest session, obtained the way a storefront would obtain one. */
    private String guestToken(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeSlug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    /**
     * A product with stock, created through the real merchant API. The SKU is generated
     * because SKU uniqueness is global rather than per-tenant, so two stores stocking the
     * same shirt collide — that is HAL-594, and this generated SKU should go away with it.
     */
    private String stockedVariant(Tenant tenant, String token) throws Exception {
        String product = mockMvc.perform(post("/api/products")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Linen shirt","description":"navy","price":49.00,
                                 "variants":[{"sku":"SHIRT-%s","price":49.00}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String variantId = JsonPath.read(product, "$.variants[0].id");
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            inventoryService.adjustStock(UUID.fromString(variantId), 5);
        } finally {
            TenantContext.clear();
        }
        return variantId;
    }

    @Test
    void aGuestCanFillACartAndPlaceAnOrder() throws Exception {
        Tenant tenant = store("linen");
        String variantId = stockedVariant(tenant, ownerToken(tenant));
        String guest = guestToken(tenant.getSlug());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":2}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cart").header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(2));

        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(98.00));
    }

    /**
     * A cart stores a variant id and a quantity, which is all it should store. But a page
     * that renders those two things and nothing else asks someone to pay for a UUID, so the
     * response resolves the labels and does the arithmetic.
     */
    @Test
    void theBagSaysWhatIsInItAndWhatItCosts() throws Exception {
        Tenant tenant = store("legible");
        String variantId = stockedVariant(tenant, ownerToken(tenant));
        String guest = guestToken(tenant.getSlug());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].productName").value("Linen shirt"))
                .andExpect(jsonPath("$.lines[0].sku").exists())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(49.00))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(147.00))
                .andExpect(jsonPath("$.totalAmount").value(147.00))
                // Units, not lines — three of one shirt is three items in the bag.
                .andExpect(jsonPath("$.itemCount").value(3));
    }

    /**
     * PUT sets, POST adds. Getting these the wrong way round turns "make that two" into
     * "add two more", which a shopper only discovers at the till.
     */
    @Test
    void changingTheQuantitySetsItRatherThanAddingToIt() throws Exception {
        Tenant tenant = store("quantity");
        String variantId = stockedVariant(tenant, ownerToken(tenant));
        String guest = guestToken(tenant.getSlug());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":3}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/cart/items/" + variantId)
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value(98.00));

        // Zero is how a shopper says "take it out", and the API accepts it as such.
        mockMvc.perform(put("/api/cart/items/" + variantId)
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isEmpty())
                .andExpect(jsonPath("$.totalAmount").value(0));
    }

    /** The shopper has to be able to see what they just did. */
    @Test
    void aGuestCanReadTheirOwnOrderBack() throws Exception {
        Tenant tenant = store("readback");
        String variantId = stockedVariant(tenant, ownerToken(tenant));
        String guest = guestToken(tenant.getSlug());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":1}"))
                .andExpect(status().isOk());

        String order = mockMvc.perform(post("/api/checkout")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/orders/" + JsonPath.read(order, "$.id").toString())
                        .header("Authorization", guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                // A receipt names what was bought, and quotes the price that was charged.
                .andExpect(jsonPath("$.lines[0].productName").value("Linen shirt"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(49.00));
    }

    /** And the merchant has to be able to see it, or the sale may as well not have happened. */
    @Test
    void theOrderAppearsForTheMerchant() throws Exception {
        Tenant tenant = store("merchant-sees");
        String owner = ownerToken(tenant);
        String variantId = stockedVariant(tenant, owner);
        String guest = guestToken(tenant.getSlug());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/checkout")
                        .header("Authorization", guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PLACED"));
    }

    /**
     * The isolation question. A guest token is minted from a slug by anyone who asks, so it
     * is the cheapest credential in the system to obtain — which makes it the natural probe
     * for reaching into another store.
     */
    @Test
    void aGuestOfOneStoreCannotBuyFromAnother() throws Exception {
        Tenant mine = store("mine");
        Tenant theirs = store("theirs");
        String theirVariant = stockedVariant(theirs, ownerToken(theirs));
        String guestOfMine = guestToken(mine.getSlug());

        // Their variant does not exist as far as this session is concerned.
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", guestOfMine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + theirVariant + "\",\"quantity\":1}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aGuestCannotReadAnotherStoresOrders() throws Exception {
        Tenant theirs = store("private");
        String theirVariant = stockedVariant(theirs, ownerToken(theirs));
        String theirGuest = guestToken(theirs.getSlug());

        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", theirGuest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + theirVariant + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        String order = mockMvc.perform(post("/api/checkout")
                        .header("Authorization", theirGuest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String nosy = guestToken(store("nosy").getSlug());

        mockMvc.perform(get("/api/orders/" + JsonPath.read(order, "$.id").toString())
                        .header("Authorization", nosy))
                .andExpect(status().isNotFound());
    }

    @Test
    void aGuestSessionForAStoreThatDoesNotExistIsA404() throws Exception {
        mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeSlug\":\"no-such-shop\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aGuestSessionNeedsAStore() throws Exception {
        mockMvc.perform(post("/api/auth/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeSlug\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A guest row owns a cart and an order; it is not an account. Nobody knows its password
     * because nobody chose one, so the login door stays shut even for someone who learns
     * the synthetic address.
     */
    @Test
    void aGuestIdentityCannotBeLoggedInTo() throws Exception {
        Tenant tenant = store("login");
        guestToken(tenant.getSlug());

        // Scoped to this tenant: the suite shares a database, and any other test's shopper
        // would answer to its own password and make this pass for the wrong reason.
        AppUser guest = userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.SHOPPER)
                .filter(user -> tenant.getId().equals(user.getTenantId()))
                .findFirst()
                .orElseThrow();
        assertThat(guest.getEmail()).endsWith("@guest.invalid");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + guest.getEmail() + "\",\"password\":\"password\"}"))
                .andExpect(status().is4xxClientError());
    }
}
