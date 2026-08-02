package com.mercala.identity.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.identity.AppUser;
import com.mercala.identity.Tenant;
import com.mercala.identity.service.RegistrationService;
import com.mercala.identity.web.dto.CreateTenantRequest;
import com.mercala.identity.web.dto.CreateUserRequest;
import com.mercala.identity.web.dto.TenantResponse;
import com.mercala.identity.web.dto.UpdateTenantRequest;
import com.mercala.identity.web.dto.UserResponse;

import jakarta.validation.Valid;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/tenants")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final com.mercala.platform.security.JwtService jwtService;

    public RegistrationController(RegistrationService registrationService,
                                  com.mercala.platform.security.JwtService jwtService) {
        this.registrationService = registrationService;
        this.jwtService = jwtService;
    }

    @SecurityRequirements  // Public: store signup happens before any token exists.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = registrationService.createTenant(request);
        return new TenantResponse(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getStatus().name(), tenant.getDescription());
    }

    /**
     * The dashboard onboarding step (HAL-552): a signed-in, storeless user creates
     * their store. The response carries a fresh token because the caller's current
     * JWT has no tenant claim — without the reissue, their session would keep seeing
     * an empty world until they logged in again.
     */
    @PostMapping("/me")
    @ResponseStatus(HttpStatus.CREATED)
    public com.mercala.identity.web.dto.StoreCreatedResponse createMyStore(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.mercala.platform.security.AuthenticatedUser principal,
            @Valid @RequestBody com.mercala.identity.web.dto.CreateStoreRequest request) {
        Tenant tenant = registrationService.createStoreFor(principal.userId(), request);
        com.mercala.identity.AppUser owner = registrationService.getUser(principal.userId());
        return new com.mercala.identity.web.dto.StoreCreatedResponse(
                tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getStatus().name(),
                tenant.getDescription(),
                jwtService.issue(owner), "Bearer", jwtService.getExpirationSeconds());
    }

    /**
     * Edits the caller's own store profile — the settings page. {@code /me}, not
     * {@code /{slug}}: the tenant is whatever the JWT says, so the endpoint cannot
     * be pointed at someone else's store.
     */
    @PatchMapping("/me")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public TenantResponse updateMyStore(@Valid @RequestBody UpdateTenantRequest request) {
        Tenant tenant = registrationService.updateProfile(request);
        return new TenantResponse(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getStatus().name(), tenant.getDescription());
    }

    @PostMapping("/{slug}/users")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")   // owner-only — RBAC (HAL-127)
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse addUser(@PathVariable String slug, @Valid @RequestBody CreateUserRequest request) {
        AppUser user = registrationService.addUser(slug, request);
        return new UserResponse(user.getId(), user.getEmail(), user.getRole());
    }

    @GetMapping("/{slug}/users")
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public List<UserResponse> getUsers(@PathVariable String slug) {
        return registrationService.getUsers(slug).stream()
                .map(user -> new UserResponse(user.getId(), user.getEmail(), user.getRole()))
                .toList();
    }
}
