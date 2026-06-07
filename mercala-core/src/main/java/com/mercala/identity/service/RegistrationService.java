package com.mercala.identity.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.identity.exception.ResourceConflictException;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.identity.web.dto.CreateTenantRequest;
import com.mercala.identity.web.dto.CreateUserRequest;

@Service
@Transactional
public class RegistrationService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(TenantRepository tenantRepository, AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Tenant createTenant(CreateTenantRequest request) {
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new ResourceConflictException("Tenant slug already exists: " + request.slug());
        }

        Tenant tenant = new Tenant(request.slug(), request.name());
        tenant = tenantRepository.save(tenant);

        String hashedPassword = passwordEncoder.encode(request.ownerPassword());
        AppUser owner = new AppUser(tenant.getId(), request.ownerEmail(), hashedPassword, Role.MERCHANT_OWNER);
        userRepository.save(owner);

        return tenant;
    }

    public AppUser addUser(String tenantSlug, CreateUserRequest request) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantSlug));

        if (userRepository.existsByTenantIdAndEmail(tenant.getId(), request.email())) {
            throw new ResourceConflictException("User email already exists within tenant: " + request.email());
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        AppUser user = new AppUser(tenant.getId(), request.email(), hashedPassword, request.role());
        return userRepository.save(user);
    }
}
