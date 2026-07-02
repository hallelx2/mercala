package com.mercala.contracts.event;

import java.util.UUID;

/**
 * Shared Kafka event published on 'product.events' when a product is added or updated.
 */
public record ProductEvent(
        UUID productId,
        UUID tenantId,
        String eventType // e.g. "ADDED", "UPDATED", "DELETED"
) {}
