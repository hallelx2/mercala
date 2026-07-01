package com.mercala.inventory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockItemRepository extends JpaRepository<StockItem, UUID> {
    
    /**
     * Find stock item by product variant ID.
     * Note: Tenant isolation filter will automatically apply if active.
     */
    Optional<StockItem> findByVariantId(UUID variantId);
}
