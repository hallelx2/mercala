package com.mercala.identity.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.identity.web.dto.LoginRequest;
import com.mercala.identity.web.dto.LoginResponse;
import com.mercala.identity.web.dto.MeResponse;
import com.mercala.platform.security.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Public: exchange tenant slug + email + password for a signed JWT. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request);
        return new LoginResponse(result.token(), "Bearer", result.expiresIn());
    }

    /** Authenticated: returns the current principal extracted from the JWT. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new MeResponse(principal.userId(), principal.tenantId(), principal.email(), principal.role());
    }
}
