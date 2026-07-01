package com.mercala.cart.web.dto;

import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        UUID userId,
        List<CartLineResponse> lines
) {}
