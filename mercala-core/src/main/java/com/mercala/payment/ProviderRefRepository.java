package com.mercala.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProviderRefRepository extends JpaRepository<ProviderRef, UUID> {
    Optional<ProviderRef> findByTenantIdAndProviderAndEnabledTrue(UUID tenantId, String provider);
}
