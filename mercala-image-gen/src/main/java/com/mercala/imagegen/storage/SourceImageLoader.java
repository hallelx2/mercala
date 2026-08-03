package com.mercala.imagegen.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches the merchant's original photograph so a provider can retouch it.
 *
 * <h2>Why this is no longer an HTTP GET</h2>
 *
 * <p>It was one, and that was the bug. The URL names an object in the private bucket, so an
 * anonymous request for it returns 403 — enhancement worked on a laptop, where the storage
 * service had made the local bucket world-readable, and had never once worked in the
 * deployed stack (HAL-425).
 *
 * <p>Reading through the storage client fixes the 403 and removes a class of problem with
 * it. The URL arrives on a Kafka event that originated in a chat message, and an HTTP
 * client pointed at attacker-influenced input, on a host carrying an IAM instance profile,
 * is an SSRF proxy waiting to happen — the metadata endpoint is one redirect away. There is
 * now no outbound request to redirect: the URL is parsed into a bucket and a key, both
 * checked against this deployment's own buckets, and read with credentials.
 *
 * <p>The size cap stays. A provider request is assembled in memory, and an unbounded read
 * decides how much memory that is.
 */
@Component
public class SourceImageLoader {

    private static final Logger log = LoggerFactory.getLogger(SourceImageLoader.class);

    private final StorageService storage;
    private final long maxBytes;

    public SourceImageLoader(
            StorageService storage,
            @Value("${mercala.image-gen.source.max-bytes:15728640}") long maxBytes) {
        this.storage = storage;
        this.maxBytes = maxBytes;
    }

    public byte[] load(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("An enhancement needs a source image URL");
        }

        byte[] bytes = storage.readObject(url);

        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("The source image is empty");
        }
        if (bytes.length > maxBytes) {
            throw new IllegalStateException(
                    "The source image is " + bytes.length + " bytes, over the " + maxBytes + " byte limit");
        }

        log.info("Loaded {} byte source image from {}", bytes.length, url);
        return bytes;
    }
}
