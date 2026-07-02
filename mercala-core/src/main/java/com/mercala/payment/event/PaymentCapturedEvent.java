package com.mercala.payment.event;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCapturedEvent(
    UUID orderId,
    UUID tenantId,
    String provider,
    String providerReference,
    BigDecimal amount,
    String currency
) {}
