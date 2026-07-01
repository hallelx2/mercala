package com.mercala.catalog;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantRepository extends JpaRepository<Variant, UUID> {

    Optional<Variant> findBySku(String sku);

    @org.springframework.data.jpa.repository.Query("select v from Variant v join v.product p where v.id = :id and p.tenantId = :tenantId")
    Optional<Variant> findByIdAndTenantId(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
