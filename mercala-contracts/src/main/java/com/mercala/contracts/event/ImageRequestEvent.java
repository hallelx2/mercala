package com.mercala.contracts.event;

import java.util.UUID;

/**
 * Shared Kafka event published on 'image.requests' when an image generation is requested.
 */
public record ImageRequestEvent(
        UUID eventId,
        UUID productId,
        UUID tenantId,
        String prompt
) {
    public ImageRequestEvent(UUID productId, UUID tenantId, String prompt) {
        this(UUID.randomUUID(), productId, tenantId, prompt);
    }
}
