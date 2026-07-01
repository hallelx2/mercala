package com.mercala.cart.web.dto;

import java.util.UUID;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull(message = "Variant ID is required")
        UUID variantId,

        @Min(value = 1, message = "Quantity must be at least 1")
        @jakarta.validation.constraints.Max(value = 10000, message = "Quantity cannot exceed 10000")
        int quantity
) {}
