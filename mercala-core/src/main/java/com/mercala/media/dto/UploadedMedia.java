package com.mercala.media.dto;

import java.util.UUID;

/**
 * What an upload returns: enough for the client to show the image back and for the agent to
 * name it as the source of an enhancement.
 *
 * @param id          the stored asset
 * @param url         where the bytes now live
 * @param contentType as determined from the bytes, not as the caller declared it
 * @param sizeBytes   the stored size
 * @param productId   the product it was attached to, if any
 */
public record UploadedMedia(
        UUID id,
        String url,
        String contentType,
        long sizeBytes,
        UUID productId
) {
}
