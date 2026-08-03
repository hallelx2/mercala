package com.mercala.media;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.security.JwtService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replay reads the whole {@code image.results} topic back, across every store on the
 * platform. It was reachable with no token at all — {@code /api/media/**} was
 * {@code permitAll} on a wildcard — so anyone who found the path could trigger it as often
 * as they liked (HAL-495).
 *
 * <p>Two properties are asserted here, and the second is the one a future change is most
 * likely to break: anonymous callers are refused, and being a legitimate, authenticated
 * merchant is not enough either. Replay is cross-tenant; a merchant role reaching it would
 * be a tenancy hole rather than a convenience.
 */
@AutoConfigureMockMvc
class MediaReplayAuthorizationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String tokenFor(Role role) {
        Tenant tenant = tenantRepository.save(
                new Tenant("shop-" + UUID.randomUUID().toString().substring(0, 8), "Linen Shop"));
        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), "user-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("password"), role));
        return "Bearer " + jwtService.issue(user);
    }

    @Test
    void anonymousReplayIsRefused() throws Exception {
        mockMvc.perform(post("/api/media/replay"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMerchantOwnerCannotReplayTheWholePlatform() throws Exception {
        mockMvc.perform(post("/api/media/replay")
                        .header("Authorization", tokenFor(Role.MERCHANT_OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void neitherCanStaffOrAShopper() throws Exception {
        mockMvc.perform(post("/api/media/replay")
                        .header("Authorization", tokenFor(Role.MERCHANT_STAFF)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/media/replay")
                        .header("Authorization", tokenFor(Role.SHOPPER)))
                .andExpect(status().isForbidden());
    }

    /**
     * The wildcard is gone rather than narrowed, so an endpoint added under
     * {@code /api/media} tomorrow is authenticated by default instead of silently public.
     * A path that does not exist proves the rule rather than the handler: 401, not 404.
     */
    @Test
    void anEndpointThatDoesNotExistUnderMediaIsStillNotAnonymous() throws Exception {
        mockMvc.perform(post("/api/media/something-added-later"))
                .andExpect(status().isUnauthorized());
    }
}
