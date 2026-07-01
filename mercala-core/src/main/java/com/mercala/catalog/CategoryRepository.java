package com.mercala.catalog;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByTenantIdAndSlug(UUID tenantId, String slug);

    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);
}
