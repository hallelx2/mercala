package com.mercala.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMembershipRepository extends JpaRepository<StoreMembership, UUID> {

    List<StoreMembership> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<StoreMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    boolean existsByUserIdAndTenantId(UUID userId, UUID tenantId);
}
