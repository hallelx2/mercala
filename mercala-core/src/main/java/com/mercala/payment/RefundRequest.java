package com.mercala.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record RefundRequest(
    UUID paymentId,
    String providerReference,
    BigDecimal amount,
    String reason
) {}
