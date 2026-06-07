package com.mercala.identity.web;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.identity.exception.InvalidCredentialsException;
import com.mercala.identity.web.dto.LoginRequest;
import com.mercala.platform.security.JwtService;

/**
 * Authenticates a user within a tenant and issues a JWT. All failure paths throw the
 * same {@link InvalidCredentialsException} so the API can't be used to probe which
 * tenants/emails exist.
 */
@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(TenantRepository tenantRepository, AppUserRepository userRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AuthResult login(LoginRequest request) {
        Tenant tenant = tenantRepository.findBySlug(request.tenantSlug())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        AppUser user = userRepository.findByTenantIdAndEmail(tenant.getId(), request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        return new AuthResult(jwtService.issue(user), jwtService.getExpirationSeconds());
    }

    public record AuthResult(String token, long expiresIn) {}
}
