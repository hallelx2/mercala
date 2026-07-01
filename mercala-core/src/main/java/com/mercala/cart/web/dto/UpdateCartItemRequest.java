package com.mercala.cart.web.dto;

import jakarta.validation.constraints.Min;

public record UpdateCartItemRequest(
        @Min(value = 0, message = "Quantity cannot be negative")
        int quantity
) {}
