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

    public AuthController(AuthService authService, TenantRepository tenantRepository) {
        this.authService = authService;
        this.tenantRepository = tenantRepository;
    }

    /** Public: exchange tenant slug + email + password for a signed JWT. */
    @SecurityRequirements  // Public: this is where a token comes from.
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return new LoginResponse(result.token(), "Bearer", result.expiresIn());
    }

    /** Authenticated: returns the current principal extracted from the JWT. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Tenant tenant = tenantRepository.findById(principal.tenantId()).orElse(null);
        return new MeResponse(
                principal.userId(),
                principal.tenantId(),
                principal.email(),
                principal.role(),
                tenant != null ? tenant.getSlug() : null,
                tenant != null ? tenant.getName() : null,
                tenant != null ? tenant.getDescription() : null);
    }
}
