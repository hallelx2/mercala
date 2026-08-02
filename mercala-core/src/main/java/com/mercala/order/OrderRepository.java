package com.mercala.order;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("select o from Order o where o.idempotencyKey = :idempotencyKey and o.tenantId = :tenantId")
    Optional<Order> findByIdempotencyKeyAndTenantId(@Param("idempotencyKey") String idempotencyKey, @Param("tenantId") UUID tenantId);

    // Tenant scoping is deliberately absent from the signatures below. The Hibernate tenant
    // filter and Postgres RLS both apply to these queries, and restating the predicate here
    // would imply the isolation lives in the query rather than in those two layers — which
    // is exactly the misunderstanding that leads someone to remove one of them later.
    //
    // The userId filters are a different concern: they separate shoppers from each other
    // *within* a tenant, which neither the filter nor RLS does.
    //
    // Named findAllBy rather than findAll so as not to override JpaRepository's inherited
    // method and change behaviour for existing callers.

    Page<Order> findAllBy(Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findByUserId(UUID userId, Pageable pageable);

    Page<Order> findByUserIdAndStatus(UUID userId, OrderStatus status, Pageable pageable);
}
