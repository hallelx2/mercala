package com.mercala.media;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    /**
     * Finds all images associated with a specific product.
     *
     * @param productId The ID of the product
     * @return List of product images
     */
    List<ProductImage> findByProductId(UUID productId);

    /**
     * Explicitly tenant-scoped, newest first.
     *
     * <p>The Hibernate {@code tenantFilter} is enabled by {@code TenantFilterAspect} on the
     * session the surrounding transaction opened — which means it only applies when there
     * is one. A read straight out of a controller has no transaction, gets its own
     * EntityManager, and quietly returns rows the filter would have removed. Naming the
     * tenant in the query removes the dependency on that timing entirely, and leaves the
     * filter and RLS as the second and third layers rather than the only ones.
     */
    List<ProductImage> findByTenantIdAndProductIdOrderByCreatedAtDesc(UUID tenantId, UUID productId);
}
