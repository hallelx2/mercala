package com.mercala.order.event;

import java.util.UUID;

public record OrderPlacedEvent(UUID orderId, UUID userId, UUID tenantId) {}
