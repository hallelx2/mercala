package com.mercala.order;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("select o from Order o where o.idempotencyKey = :idempotencyKey and o.tenantId = :tenantId")
    Optional<Order> findByIdempotencyKeyAndTenantId(@Param("idempotencyKey") String idempotencyKey, @Param("tenantId") UUID tenantId);
}
