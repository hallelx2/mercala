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

    /**
     * Atomically claims a store for a storeless user. The {@code tenantId is null}
     * predicate is the whole point: two concurrent store creations for one user both
     * pass a read-then-check, but only one of these updates can match — the loser
     * updates zero rows and its transaction (tenant insert included) rolls back.
     * {@code clearAutomatically} evicts the stale entity so a same-request reload
     * (open-in-view shares the persistence context) sees the new tenant.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update AppUser u set u.tenantId = :tenantId where u.id = :userId and u.tenantId is null")
    int attachStoreIfNone(java.util.UUID userId, java.util.UUID tenantId);
}
