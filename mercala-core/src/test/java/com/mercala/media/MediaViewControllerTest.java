package com.mercala.media;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.security.JwtService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant uploads stay in the private bucket, so this endpoint is the only way one reaches
 * a browser. It hands out a signature, which makes the ownership check the whole point: a
 * mistake here is not a broken image, it is another merchant's photograph.
 */
@AutoConfigureMockMvc
class MediaViewControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockBean private MediaObjectStorage storage;

    private static final String PRESIGNED = "http://localhost:9000/mercala-images/x?X-Amz-Signature=abc";

    private record Store(UUID tenantId, String token) {}

    private Store store() {
        Tenant tenant = tenantRepository.save(
                new Tenant("shop-" + UUID.randomUUID().toString().substring(0, 8), "Linen Shop"));
        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), "owner-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("password"), Role.MERCHANT_OWNER));
        return new Store(tenant.getId(), "Bearer " + jwtService.issue(user));
    }

    private static String urlFor(UUID tenantId) {
        return "http://localhost:9000/mercala-images/" + tenantId + "/uploads/photo.png";
    }

    @Test
    void aMerchantIsRedirectedToASignedUrlForTheirOwnUpload() throws Exception {
        Store shop = store();
        String key = shop.tenantId() + "/uploads/photo.png";
        when(storage.objectKeyOf(anyString())).thenReturn(key);
        when(storage.presignedView(eq(key), any(Duration.class))).thenReturn(PRESIGNED);

        mockMvc.perform(get("/api/media/view")
                        .param("url", urlFor(shop.tenantId()))
                        .header("Authorization", shop.token()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", PRESIGNED));
    }

    /**
     * The one that matters. The key's tenant prefix is the ownership record, and a
     * signature for somebody else's object would be handed straight to the browser.
     */
    @Test
    void anotherStoresObjectIsRefusedWithoutMintingASignature() throws Exception {
        Store mine = store();
        UUID theirs = UUID.randomUUID();
        when(storage.objectKeyOf(anyString())).thenReturn(theirs + "/uploads/photo.png");

        mockMvc.perform(get("/api/media/view")
                        .param("url", urlFor(theirs))
                        .header("Authorization", mine.token()))
                .andExpect(status().isForbidden());

        verify(storage, never()).presignedView(anyString(), any(Duration.class));
    }

    /**
     * A tenant id is a prefix, and prefixes are not the same as path segments: an object
     * under a *different* tenant whose id merely starts with the caller's would otherwise
     * pass a naive startsWith.
     */
    @Test
    void aKeyThatMerelyStartsWithTheTenantIdIsNotTheirs() throws Exception {
        Store mine = store();
        when(storage.objectKeyOf(anyString())).thenReturn(mine.tenantId() + "-other/uploads/photo.png");

        mockMvc.perform(get("/api/media/view")
                        .param("url", urlFor(mine.tenantId()))
                        .header("Authorization", mine.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousRequestIsRefused() throws Exception {
        mockMvc.perform(get("/api/media/view").param("url", urlFor(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        verify(storage, never()).presignedView(anyString(), any(Duration.class));
    }

    @Test
    void aUrlFromSomewhereElseIsABadRequest() throws Exception {
        Store shop = store();
        when(storage.objectKeyOf(anyString()))
                .thenThrow(new IllegalArgumentException("That image does not belong to this store's uploads"));

        mockMvc.perform(get("/api/media/view")
                        .param("url", "https://evil.example/photo.png")
                        .header("Authorization", shop.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storageBeingDownIsA503RatherThanA500() throws Exception {
        Store shop = store();
        when(storage.objectKeyOf(anyString())).thenReturn(shop.tenantId() + "/uploads/photo.png");
        when(storage.presignedView(anyString(), any(Duration.class)))
                .thenThrow(new MediaObjectStorage.MediaStorageException("Image storage is not available right now"));

        mockMvc.perform(get("/api/media/view")
                        .param("url", urlFor(shop.tenantId()))
                        .header("Authorization", shop.token()))
                .andExpect(status().isServiceUnavailable());
    }
}
