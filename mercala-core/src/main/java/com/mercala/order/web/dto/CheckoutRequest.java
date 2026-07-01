package com.mercala.order.web.dto;

public record CheckoutRequest(
        String idempotencyKey
) {}
