package com.mercala.cart;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    @Query("select c from Cart c where c.userId = :userId and c.tenantId = :tenantId")
    Optional<Cart> findByUserIdAndTenantId(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);
}
