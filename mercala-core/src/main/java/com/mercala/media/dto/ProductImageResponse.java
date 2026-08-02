package com.mercala.media.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One image attached to a product.
 *
 * @param createdAt what lets a client tell a newly-arrived render from the one it was
 *                  already showing, which is how "your enhanced photo is ready" is
 *                  detected without a websocket
 */
public record ProductImageResponse(
        UUID id,
        UUID productId,
        String url,
        Instant createdAt
) {
}
