package com.mercala.order.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mercala.order.OrderStatus;

/**
 * An order as a client reads it.
 *
 * @param createdAt when it was placed. The entity has always carried this; the response did
 *                  not, which left every time-shaped question unanswerable on the client —
 *                  when an order arrived, how this week compares to the last, how long
 *                  since the store sold anything at all. Appended rather than inserted, so
 *                  the record's existing positional shape is untouched.
 */
public record OrderResponse(
        UUID id,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String idempotencyKey,
        List<OrderLineResponse> lines,
        Instant createdAt
) {}
