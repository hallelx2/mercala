package com.mercala.cart.web.dto;

import jakarta.validation.constraints.Min;

public record UpdateCartItemRequest(
        @Min(value = 0, message = "Quantity cannot be negative")
        @jakarta.validation.constraints.Max(value = 10000, message = "Quantity cannot exceed 10000")
        int quantity
) {}
