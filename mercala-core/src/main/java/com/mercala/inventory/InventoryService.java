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
        // First validate variant exists and is accessible to current tenant
        getRequiredVariant(variantId);
        
        return stockItemRepository.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("StockItem not found for variant: " + variantId));
    }

    public void adjustStock(UUID variantId, int newQuantity) {
        UUID tenantId = getRequiredTenantId();
        // Validate variant exists and is accessible to current tenant
        getRequiredVariant(variantId);

        StockItem item = stockItemRepository.findByVariantId(variantId)
                .orElseGet(() -> new StockItem(tenantId, variantId, 0));
        
        item.setQuantity(newQuantity);
        stockItemRepository.save(item);
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

    private Variant getRequiredVariant(UUID variantId) {
        UUID tenantId = getRequiredTenantId();
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
