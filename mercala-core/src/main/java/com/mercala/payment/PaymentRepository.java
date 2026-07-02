package com.mercala.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Payment> findByOrderIdAndTenantId(UUID orderId, UUID tenantId);
    Optional<Payment> findByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);
    Optional<Payment> findByProviderReference(String providerReference);
}
