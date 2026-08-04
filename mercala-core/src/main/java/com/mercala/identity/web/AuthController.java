package com.mercala.identity.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.identity.web.dto.LoginRequest;
import com.mercala.identity.web.dto.LoginResponse;
import com.mercala.identity.web.dto.MeResponse;
import com.mercala.platform.security.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TenantRepository tenantRepository;
    private final com.mercala.identity.AppUserRepository userRepository;
    private final com.mercala.identity.StoreMembershipRepository membershipRepository;
    private final com.mercala.identity.service.RegistrationService registrationService;
    private final com.mercala.platform.security.JwtService jwtService;
    private final com.mercala.identity.service.GuestSessionService guestSessionService;

    public AuthController(AuthService authService, TenantRepository tenantRepository,
                          com.mercala.identity.AppUserRepository userRepository,
                          com.mercala.identity.StoreMembershipRepository membershipRepository,
                          com.mercala.identity.service.RegistrationService registrationService,
                          com.mercala.platform.security.JwtService jwtService,
                          com.mercala.identity.service.GuestSessionService guestSessionService) {
        this.guestSessionService = guestSessionService;
        this.authService = authService;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.registrationService = registrationService;
        this.jwtService = jwtService;
    }

    /**
     * Public: self-serve signup (HAL-552) — a person, not a store. Returns a session
     * directly; making someone log in to the account they created ten seconds ago
     * would be a pointless second step.
     */
    @SecurityRequirements
    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public LoginResponse register(@Valid @RequestBody com.mercala.identity.web.dto.RegisterRequest request) {
        var user = registrationService.register(request);
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.getExpirationSeconds());
    }

    /**
     * Public: a session for a shopper who has not signed up for anything.
     *
     * <p>Under {@code /api/auth} rather than {@code /api/public/stores/{slug}} because it
     * mints a token, and because the public storefront rules permit {@code GET} only —
     * "nothing under /api/public may ever mutate" is an invariant worth keeping rather
     * than an inconvenience to route around.
     *
     * <p>The store slug is the whole input: with no account to look a tenant up from, the
     * storefront the shopper is standing in is what scopes the session. An unknown slug is
     * a 404, same as browsing one.
     */
    @SecurityRequirements
    @PostMapping("/guest")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public LoginResponse guest(
            @Valid @RequestBody com.mercala.identity.web.dto.GuestSessionRequest request) {
        var guest = guestSessionService.createFor(request.storeSlug());
        return new LoginResponse(jwtService.issue(guest), "Bearer", jwtService.getExpirationSeconds());
    }

    /**
     * Public: exchange credentials for a signed JWT. The store slug is optional since
     * HAL-552 — without it, the password picks among the accounts under the email.
     */
    @SecurityRequirements  // Public: this is where a token comes from.
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return new LoginResponse(result.token(), "Bearer", result.expiresIn());
    }

    /**
     * Authenticated: switches the session's active store and returns the reissued
     * token. Membership-gated; a store you don't belong to 404s exactly like one
     * that doesn't exist.
     */
    @PostMapping("/switch-store")
    public LoginResponse switchStore(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody com.mercala.identity.web.dto.SwitchStoreRequest request) {
        AuthService.AuthResult result = authService.switchStore(principal.userId(), request.slug());
        return new LoginResponse(result.token(), "Bearer", result.expiresIn());
    }

    /** Authenticated: the current principal, their active store, and all their stores. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Tenant tenant = principal.tenantId() != null
                ? tenantRepository.findById(principal.tenantId()).orElse(null)
                : null;
        String userName = userRepository.findById(principal.userId())
                .map(com.mercala.identity.AppUser::getName)
                .orElse(null);

        var memberships = membershipRepository.findByUserIdOrderByCreatedAtAsc(principal.userId());
        var tenantsById = new java.util.HashMap<java.util.UUID, Tenant>();
        tenantRepository.findAllById(memberships.stream()
                        .map(com.mercala.identity.StoreMembership::getTenantId).toList())
                .forEach(t -> tenantsById.put(t.getId(), t));
        var stores = memberships.stream()
                .map(m -> {
                    Tenant t = tenantsById.get(m.getTenantId());
                    return t == null ? null : new com.mercala.identity.web.dto.StoreSummary(
                            t.getId(), t.getSlug(), t.getName(), m.getRole());
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return new MeResponse(
                principal.userId(),
                principal.tenantId(),
                principal.email(),
                principal.role(),
                userName,
                tenant != null ? tenant.getSlug() : null,
                tenant != null ? tenant.getName() : null,
                tenant != null ? tenant.getDescription() : null,
                stores);
    }
}
