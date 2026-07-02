package com.mercala.contracts.event;

import java.util.UUID;

/**
 * Shared Kafka event published on 'image.requests' when an image generation is requested.
 */
public record ImageRequestEvent(
        UUID productId,
        UUID tenantId,
        String prompt
) {}
