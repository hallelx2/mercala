package com.mercala.order.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineResponse(
        UUID id,
        UUID variantId,
        int quantity,
        BigDecimal unitPrice
) {}
