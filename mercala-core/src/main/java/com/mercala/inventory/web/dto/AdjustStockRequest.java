package com.mercala.inventory.web.dto;

import jakarta.validation.constraints.Min;

public record AdjustStockRequest(
        @Min(value = 0, message = "Quantity cannot be negative")
        int quantity
) {}
