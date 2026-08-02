package com.mercala.contracts.event;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Shared Kafka event published on 'image.results' when an image has been produced and stored.
 *
 * <p>Carries the mode and the source it came from, because a merchant looking at an
 * enhanced photo needs to see it beside their original — an enhanced image with no link
 * back to what it was made from is just a second, unexplained picture.
 *
 * @param mode           whether this image was generated or enhanced; defaults to generated
 *                       so events written before the field existed still read correctly
 * @param sourceImageUrl the merchant's original, on an enhancement
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageResultEvent(
        UUID eventId,
        UUID productId,
        UUID tenantId,
        String imageUrl,
        ImageMode mode,
        String sourceImageUrl
) {

    public ImageResultEvent {
        mode = mode == null ? ImageMode.GENERATE : mode;
    }

    public ImageResultEvent(UUID productId, UUID tenantId, String imageUrl) {
        this(UUID.randomUUID(), productId, tenantId, imageUrl, ImageMode.GENERATE, null);
    }

    /**
     * With the event id supplied — the caller is replaying, deduplicating, or asserting on
     * a specific id. This was the canonical shape before the record grew a mode, so it also
     * keeps existing call sites compiling.
     */
    public ImageResultEvent(UUID eventId, UUID productId, UUID tenantId, String imageUrl) {
        this(eventId, productId, tenantId, imageUrl, ImageMode.GENERATE, null);
    }

    public static ImageResultEvent enhanced(
            UUID productId, UUID tenantId, String imageUrl, String sourceImageUrl) {
        return new ImageResultEvent(
                UUID.randomUUID(), productId, tenantId, imageUrl, ImageMode.ENHANCE, sourceImageUrl);
    }
}
