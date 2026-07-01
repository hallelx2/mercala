package com.mercala.cart.web.dto;

import java.util.UUID;

public record CartLineResponse(
        UUID id,
        UUID variantId,
        int quantity
) {}
