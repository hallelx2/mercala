package com.mercala.contracts.event;

import java.util.UUID;

/**
 * Shared Kafka event published on 'image.results' when an image has been successfully generated and stored.
 */
public record ImageResultEvent(
        UUID productId,
        UUID tenantId,
        String imageUrl
) {}
