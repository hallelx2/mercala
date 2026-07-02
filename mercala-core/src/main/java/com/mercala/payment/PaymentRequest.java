package com.mercala.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRequest(
    UUID orderId,
    BigDecimal amount,
    String currency,
    String idempotencyKey,
    String returnUrl
) {}
