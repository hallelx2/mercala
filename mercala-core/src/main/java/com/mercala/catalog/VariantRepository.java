package com.mercala.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantRepository extends JpaRepository<Variant, UUID> {

    Optional<Variant> findBySku(String sku);

    @org.springframework.data.jpa.repository.Query("select v from Variant v join v.product p where v.id = :id and p.tenantId = :tenantId")
    Optional<Variant> findByIdAndTenantId(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    /**
     * A whole cart or order's worth of variants in one query. The product is fetch-joined
     * because every caller wants its name — leaving it lazy would put the N+1 back one
     * layer down, where it is harder to see.
     */
    @org.springframework.data.jpa.repository.Query(
            "select v from Variant v join fetch v.product p where v.id in :ids and p.tenantId = :tenantId")
    List<Variant> findAllByIdInAndTenantId(
            @org.springframework.data.repository.query.Param("ids") Collection<UUID> ids,
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
