package com.mercala.inventory.web.dto;

import java.util.UUID;

public record StockResponse(
        UUID id,
        UUID variantId,
        int quantity,
        int reservedQuantity,
        int availableQuantity
) {}
