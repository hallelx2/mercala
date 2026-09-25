package com.mercala.identity.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.StoreMembership;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.identity.exception.ResourceConflictException;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.identity.web.dto.CreateTenantRequest;
import com.mercala.identity.web.dto.CreateUserRequest;
import com.mercala.platform.multitenancy.TenantContext;

import java.util.List;

@Service
@Transactional
public class RegistrationService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final com.mercala.identity.StoreMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(TenantRepository tenantRepository, AppUserRepository userRepository,
                               com.mercala.identity.StoreMembershipRepository membershipRepository,
                               PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Self-serve signup (HAL-552): a person, no store. The store comes later via
     * {@link #createStoreFor}. Email is unique across ALL accounts, not just tenantless
     * ones — otherwise slugless login could never disambiguate this user later.
     */
    public AppUser register(com.mercala.identity.web.dto.RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResourceConflictException("An account already exists for " + request.email());
        }
        AppUser user = new AppUser(null, request.email(),
                passwordEncoder.encode(request.password()), Role.MERCHANT_OWNER);
        user.setName(request.name());
        return userRepository.save(user);
    }

    /**
     * Creates a store for the caller — their first or their fifth (HAL-556). The new
     * store gets an owner membership and becomes the active one; concurrent calls are
     * both valid (two real stores, two memberships) and merely race for which ends up
     * active, which the store switcher makes a non-event.
     */
    public StoreCreation createStoreFor(java.util.UUID userId, com.mercala.identity.web.dto.CreateStoreRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new ResourceConflictException("Tenant slug already exists: " + request.slug());
        }

        Tenant tenant = new Tenant(request.slug(), request.name());
        tenant.setDescription(request.description());
        tenant = tenantRepository.save(tenant);

        membershipRepository.save(new StoreMembership(userId, tenant.getId(), Role.MERCHANT_OWNER));
        user.setTenantId(tenant.getId());
        // The caller's session has no tenant yet — that is the whole point of this call —
        // so nothing has set app.current_tenant, and row-level security refuses a row
        // whose tenant_id matches no context. Adopting the new tenant for the rest of the
        // transaction is what makes the write legal, and it is also simply true: from the
        // moment this user owns a store, this is the store they are acting in.
        AppUser owner = withTenant(tenant.getId(), () -> userRepository.save(user));
        // The owner is returned rather than looked up again by the caller. A second
        // lookup would run with the caller's still-tenantless session, and the row it
        // wants now carries a tenant_id — so row-level security would hide the very user
        // this call just created a store for, and the request would 404 after succeeding.
        return new StoreCreation(tenant, owner);
    }

    @Transactional(readOnly = true)
    public AppUser getUser(java.util.UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    public Tenant createTenant(CreateTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new ResourceConflictException("Tenant slug already exists: " + request.slug());
        }

        Tenant tenant = new Tenant(request.slug(), request.name());
        tenant.setDescription(request.description());
        tenant = tenantRepository.save(tenant);

        String hashedPassword = passwordEncoder.encode(request.ownerPassword());
        AppUser owner = new AppUser(tenant.getId(), request.ownerEmail(), hashedPassword, Role.MERCHANT_OWNER);
        // Same reason as createStoreFor: this runs with no tenant in context, and the
        // owner row it writes belongs to the tenant being created.
        AppUser saved = withTenant(tenant.getId(), () -> userRepository.save(owner));
        membershipRepository.save(new StoreMembership(saved.getId(), tenant.getId(), Role.MERCHANT_OWNER));

        return tenant;
    }

    /**
     * Updates the caller's own store profile. The tenant comes from the security
     * context, never from the request — there is no way to name another store here,
     * which is what makes this safe to expose without a slug-vs-context check.
     */
    public Tenant updateProfile(com.mercala.identity.web.dto.UpdateTenantRequest request) {
        java.util.UUID tenantId = com.mercala.platform.multitenancy.TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("No tenant in context");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (request.name() != null) {
            tenant.setName(request.name());
        }
        if (request.description() != null) {
            tenant.setDescription(request.description());
        }
        return tenantRepository.save(tenant);
    }

    public AppUser addUser(String tenantSlug, CreateUserRequest request) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantSlug));

        java.util.UUID contextTenantId = com.mercala.platform.multitenancy.TenantContext.getCurrentTenant();
        if (contextTenantId == null || !contextTenantId.equals(tenant.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Cross-tenant access denied");
        }

        if (userRepository.existsByTenantIdAndEmail(tenant.getId(), request.email())) {
            throw new ResourceConflictException("User email already exists within tenant: " + request.email());
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        AppUser user = new AppUser(tenant.getId(), request.email(), hashedPassword, request.role());
        user = userRepository.save(user);
        membershipRepository.save(new StoreMembership(user.getId(), tenant.getId(), request.role()));
        return user;
    }

    @Transactional(readOnly = true)
    public List<AppUser> getUsers(String tenantSlug) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantSlug));

        java.util.UUID contextTenantId = com.mercala.platform.multitenancy.TenantContext.getCurrentTenant();
        if (contextTenantId == null || !contextTenantId.equals(tenant.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Cross-tenant access denied");
        }

        return userRepository.findAll();
    }

    /** A newly created store and the user who now owns it, as one result. */
    public record StoreCreation(Tenant tenant, AppUser owner) {}

    /**
     * Runs a repository call as though the request belonged to {@code tenantId}, then puts
     * the previous context back.
     *
     * <p>{@code TenantFilterAspect} reads {@link TenantContext} before every repository
     * call and pushes it into the connection's {@code app.current_tenant}, which is what
     * the row-level security policies compare against. Registration paths run before a
     * tenant exists, so without this the policy has nothing to match and the insert is
     * refused — a failure that only appears against a real Postgres, never in a test that
     * skips RLS.
     *
     * <p>The previous value is restored rather than cleared: this is a helper for one
     * write inside a larger request, not a way to change what the request is.
     */
    private <T> T withTenant(java.util.UUID tenantId, java.util.function.Supplier<T> work) {
        java.util.UUID previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }
}
