package com.mercala.media.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One image attached to a product.
 *
 * @param url       where the object lives. Stable, and the identity a client should key on
 *                  — but not loadable by a browser on its own, because the bucket is
 *                  private and stays that way
 * @param viewUrl   the same object as a presigned URL a browser can actually fetch. Minted
 *                  per request and short-lived, so it must never be stored or compared:
 *                  {@code url} is what stays the same between two reads of one image. Null
 *                  when storage is unavailable, which is a missing picture rather than a
 *                  failed request
 * @param createdAt what lets a client tell a newly-arrived render from the one it was
 *                  already showing, which is how "your enhanced photo is ready" is
 *                  detected without a websocket
 */
public record ProductImageResponse(
        UUID id,
        UUID productId,
        String url,
        String viewUrl,
        Instant createdAt
) {
}
