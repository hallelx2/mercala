package com.mercala.order.web.dto;

/**
 * @param idempotencyKey    replaces the {@code Idempotency-Key} header when that is absent
 * @param preferredProvider optional provider to try first, e.g. PAYSTACK. Only honoured when
 *                          the tenant has that provider configured and enabled; otherwise the
 *                          usual routing applies, so a client cannot force an unconfigured
 *                          provider by asking for it. Null means route normally.
 */
public record CheckoutRequest(
        String idempotencyKey,
        String preferredProvider
) {}
