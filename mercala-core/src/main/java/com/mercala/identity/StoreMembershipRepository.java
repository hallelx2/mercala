package com.mercala.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMembershipRepository extends JpaRepository<StoreMembership, UUID> {

    List<StoreMembership> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<StoreMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    /**
     * Slug-and-membership in ONE query, for the store switcher. A missing store and a
     * store the user doesn't belong to take the same path through the same statement —
     * two sequential lookups would let response timing distinguish them.
     */
    @org.springframework.data.jpa.repository.Query(
            "select m from StoreMembership m, Tenant t"
                    + " where m.tenantId = t.id and t.slug = :slug and m.userId = :userId")
    Optional<StoreMembership> findByUserIdAndTenantSlug(UUID userId, String slug);

    boolean existsByUserIdAndTenantId(UUID userId, UUID tenantId);
}
