package com.mercala.inventory;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.mercala.catalog.Variant;
import com.mercala.catalog.VariantRepository;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.platform.multitenancy.TenantContext;

@Service
@Transactional
public class InventoryService {

    private final StockItemRepository stockItemRepository;
    private final VariantRepository variantRepository;

    public InventoryService(StockItemRepository stockItemRepository, VariantRepository variantRepository) {
        this.stockItemRepository = stockItemRepository;
        this.variantRepository = variantRepository;
    }

    @Transactional(readOnly = true)
    public StockItem getStockItem(UUID variantId) {
        return stockItemRepository.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("StockItem not found for variant: " + variantId));
    }

    public StockItem adjustStock(UUID variantId, int newQuantity) {
        UUID tenantId = getRequiredTenantId();

        StockItem item = stockItemRepository.findByVariantId(variantId)
                .orElseGet(() -> {
                    // Validate variant exists and is accessible to current tenant only when creating new stock
                    getRequiredVariant(variantId, tenantId);
                    return new StockItem(tenantId, variantId, 0);
                });
        
        item.setQuantity(newQuantity);
        return stockItemRepository.save(item);
    }

    public void reserveStock(UUID variantId, int qty) {
        StockItem item = getStockItem(variantId);
        item.reserve(qty);
        stockItemRepository.save(item);
    }

    public void releaseStock(UUID variantId, int qty) {
        StockItem item = getStockItem(variantId);
        item.release(qty);
        stockItemRepository.save(item);
    }

    public void commitStock(UUID variantId, int qty) {
        StockItem item = getStockItem(variantId);
        item.commitReservation(qty);
        stockItemRepository.save(item);
    }

    private Variant getRequiredVariant(UUID variantId, UUID tenantId) {
        return variantRepository.findByIdAndTenantId(variantId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found: " + variantId));
    }

    private UUID getRequiredTenantId() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Tenant context is required");
        }
        return tenantId;
    }
}
