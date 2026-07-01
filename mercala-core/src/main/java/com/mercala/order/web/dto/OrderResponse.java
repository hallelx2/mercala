package com.mercala.order.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.mercala.order.OrderStatus;

public record OrderResponse(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String idempotencyKey,
        List<OrderLineResponse> lines
) {}
