package com.mercala.media;

import java.util.Collection;
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
     * Explicitly tenant-scoped, newest first, with the id breaking ties.
     *
     * <p>The tie-breaker is not decoration. {@code created_at} carries a database default,
     * and two images written in the same transaction — a generation and its enhancement,
     * or anything a test sets up in one go — share an instant exactly. Ordering by the
     * timestamp alone then returns them in whatever order the plan produced, which is
     * stable right up until it is not: this surfaced as a test that passed alone and
     * failed in a full suite run.
     *
     * <p>The Hibernate {@code tenantFilter} is enabled by {@code TenantFilterAspect} on the
     * session the surrounding transaction opened — which means it only applies when there
     * is one. A read straight out of a controller has no transaction, gets its own
     * EntityManager, and quietly returns rows the filter would have removed. Naming the
     * tenant in the query removes the dependency on that timing entirely, and leaves the
     * filter and RLS as the second and third layers rather than the only ones.
     */
    List<ProductImage> findByTenantIdAndProductIdOrderByCreatedAtDescIdDesc(UUID tenantId, UUID productId);

    /**
     * Every image for a page of products, in one query.
     *
     * <p>The alternative — asking per product — is one round trip per row, which on a
     * storefront page of twenty-four is twenty-four. Same ordering and the same explicit
     * tenant scoping as the single-product query above.
     */
    List<ProductImage> findByTenantIdAndProductIdInOrderByCreatedAtDescIdDesc(
            UUID tenantId, Collection<UUID> productIds);
}
