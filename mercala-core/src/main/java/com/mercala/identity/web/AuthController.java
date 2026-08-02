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
    private final com.mercala.identity.service.RegistrationService registrationService;
    private final com.mercala.platform.security.JwtService jwtService;

    public AuthController(AuthService authService, TenantRepository tenantRepository,
                          com.mercala.identity.AppUserRepository userRepository,
                          com.mercala.identity.service.RegistrationService registrationService,
                          com.mercala.platform.security.JwtService jwtService) {
        this.authService = authService;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
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
     * Public: exchange credentials for a signed JWT. The store slug is optional since
     * HAL-552 — without it, the password picks among the accounts under the email.
     */
    @SecurityRequirements  // Public: this is where a token comes from.
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return new LoginResponse(result.token(), "Bearer", result.expiresIn());
    }

    /** Authenticated: returns the current principal extracted from the JWT. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Tenant tenant = principal.tenantId() != null
                ? tenantRepository.findById(principal.tenantId()).orElse(null)
                : null;
        String userName = userRepository.findById(principal.userId())
                .map(com.mercala.identity.AppUser::getName)
                .orElse(null);
        return new MeResponse(
                principal.userId(),
                principal.tenantId(),
                principal.email(),
                principal.role(),
                userName,
                tenant != null ? tenant.getSlug() : null,
                tenant != null ? tenant.getName() : null,
                tenant != null ? tenant.getDescription() : null);
    }
}
