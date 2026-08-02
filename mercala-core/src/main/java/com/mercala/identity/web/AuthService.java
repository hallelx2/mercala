package com.mercala.identity.web;

import java.util.List;

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
        if (request.tenantSlug() != null && !request.tenantSlug().isBlank()) {
            return loginScopedToStore(request);
        }
        return loginByEmail(request);
    }

    private AuthResult loginScopedToStore(LoginRequest request) {
        Tenant tenant = tenantRepository.findBySlug(request.tenantSlug())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        AppUser user = userRepository.findByTenantIdAndEmail(tenant.getId(), request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        return new AuthResult(jwtService.issue(user), jwtService.getExpirationSeconds());
    }

    /**
     * Slugless login (HAL-552). The same email can exist in several stores as unrelated
     * accounts, so the candidate set is every account under the email and the
     * <em>password</em> picks between them: exactly one match logs in. Two accounts
     * sharing both email and password genuinely cannot be told apart — that case, and
     * only that case, asks for the store slug. Nothing here reveals whether an email
     * exists to a caller who doesn't hold its password.
     */
    private AuthResult loginByEmail(LoginRequest request) {
        List<AppUser> matches = userRepository.findByEmail(request.email()).stream()
                .filter(user -> passwordEncoder.matches(request.password(), user.getPasswordHash()))
                .toList();

        if (matches.isEmpty()) {
            throw new InvalidCredentialsException("Invalid credentials");
        }
        if (matches.size() > 1) {
            throw new AmbiguousAccountException(
                    "This email signs in to more than one store — include your store slug");
        }
        AppUser user = matches.get(0);
        return new AuthResult(jwtService.issue(user), jwtService.getExpirationSeconds());
    }

    public record AuthResult(String token, long expiresIn) {}

    /** 409: credentials are right for several stores at once; the slug must pick one. */
    public static class AmbiguousAccountException extends RuntimeException {
        public AmbiguousAccountException(String message) {
            super(message);
        }
    }
}
