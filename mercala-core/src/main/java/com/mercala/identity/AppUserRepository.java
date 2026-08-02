package com.mercala.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * All accounts under an email, across tenants and including tenantless ones —
     * the candidate set for slugless login, where the password disambiguates.
     */
    java.util.List<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

}
