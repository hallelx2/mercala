package com.mercala.inventory.web;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.inventory.InventoryService;
import com.mercala.inventory.StockItem;
import com.mercala.inventory.web.dto.AdjustStockRequest;
import com.mercala.inventory.web.dto.StockResponse;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{variantId}")
    @PreAuthorize("isAuthenticated()")
    public StockResponse getStock(@PathVariable UUID variantId) {
        StockItem item = inventoryService.getStockItem(variantId);
        return mapToResponse(item);
    }

    @PostMapping("/{variantId}/adjust")
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public StockResponse adjustStock(@PathVariable UUID variantId, @Valid @RequestBody AdjustStockRequest request) {
        inventoryService.adjustStock(variantId, request.quantity());
        StockItem item = inventoryService.getStockItem(variantId);
        return mapToResponse(item);
    }

    private StockResponse mapToResponse(StockItem item) {
        return new StockResponse(
                item.getId(),
                item.getVariantId(),
                item.getQuantity(),
                item.getReservedQuantity(),
                item.getAvailableQuantity()
        );
    }
}
