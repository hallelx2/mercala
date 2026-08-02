package com.mercala.contracts.event;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shared Kafka event published on 'image.requests' when an image is wanted for a product.
 *
 * <p>Two modes, one topic. {@code GENERATE} invents an image from a prompt;
 * {@code ENHANCE} takes the merchant's own photograph and retouches it. They share the
 * topic, the consumer, the storage path and the result event, because everything after
 * "produce bytes" is identical — only the call into the provider differs.
 *
 * <p>The added fields are optional with defaults, so an event written by the previous
 * three-field producer still deserialises. That matters concretely: the topic is replayable
 * and a replay after this deploy will read messages written before it.
 *
 * @param mode           {@link ImageMode#GENERATE} (default) or {@link ImageMode#ENHANCE}
 * @param sourceImageUrl the merchant's uploaded photo; required for {@code ENHANCE}
 * @param instruction    what to change about the source — ignored for {@code GENERATE}
 * @param strength       how far to move from the source: 0.0 keeps it, 1.0 abandons it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageRequestEvent(
        UUID eventId,
        UUID productId,
        UUID tenantId,
        String prompt,
        ImageMode mode,
        String sourceImageUrl,
        String instruction,
        Double strength
) {

    /** How far an enhancement drifts from the original when the caller does not say. */
    public static final double DEFAULT_STRENGTH = 0.35;

    public ImageRequestEvent {
        mode = mode == null ? ImageMode.GENERATE : mode;
        if (strength == null) {
            strength = DEFAULT_STRENGTH;
        } else if (strength < 0.0) {
            strength = 0.0;
        } else if (strength > 1.0) {
            strength = 1.0;
        }
    }

    /** Text-to-image, as before this event grew a mode. */
    public ImageRequestEvent(UUID productId, UUID tenantId, String prompt) {
        this(UUID.randomUUID(), productId, tenantId, prompt, ImageMode.GENERATE, null, null, null);
    }

    /**
     * With the event id supplied — replay, deduplication, or a test asserting on a specific
     * id. This was the canonical shape before the record grew a mode.
     */
    public ImageRequestEvent(UUID eventId, UUID productId, UUID tenantId, String prompt) {
        this(eventId, productId, tenantId, prompt, ImageMode.GENERATE, null, null, null);
    }

    /** Image-to-image over a photo the merchant uploaded. */
    public static ImageRequestEvent enhance(
            UUID productId, UUID tenantId, String sourceImageUrl, String instruction, Double strength) {
        return new ImageRequestEvent(
                UUID.randomUUID(), productId, tenantId, instruction,
                ImageMode.ENHANCE, sourceImageUrl, instruction, strength);
    }

    public boolean isEnhancement() {
        return mode == ImageMode.ENHANCE;
    }
}
