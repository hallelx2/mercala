package com.mercala.imagegen.storage;

import java.util.List;
import java.util.Locale;

/**
 * A bucket and a key, recovered from a stored URL.
 *
 * <h2>Why this is a parser and not a substring</h2>
 *
 * <p>The URL on an enhancement request travelled through a chat message, so it is
 * attacker-influenced input. It is only ever used to name an object in one of this
 * deployment's own buckets, and this is where that is enforced: the URL must begin with
 * the configured storage endpoint, and the bucket segment must be one of ours. Anything
 * else is refused before a client call is made.
 *
 * <p>Path-style URLs only ({@code https://s3.region.amazonaws.com/bucket/key}), because
 * that is the single shape this system writes — {@code MinioStorageService} builds every
 * URL it hands out as {@code endpoint + "/" + bucket + "/" + object}. Accepting the
 * virtual-hosted form as well would mean accepting a host that has not been checked.
 *
 * @param bucket which of this deployment's buckets the object lives in
 * @param key    the object key, which may contain slashes
 */
public record ObjectRef(String bucket, String key) {

    public static ObjectRef parse(String url, String endpoint, String... allowedBuckets) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("An image reference is required");
        }
        if (endpoint == null || endpoint.isBlank()) {
            // Failing closed costs a misconfigured deployment its enhancement feature.
            // Failing open would let the URL name any bucket the host's credentials reach.
            throw new IllegalStateException("No storage endpoint is configured");
        }

        String normalisedEndpoint = trimSlash(endpoint).toLowerCase(Locale.ROOT);
        String candidate = url.trim();

        if (!candidate.toLowerCase(Locale.ROOT).startsWith(normalisedEndpoint + "/")) {
            throw new IllegalArgumentException(
                    "Images must come from Mercala storage, not from an arbitrary URL");
        }

        String path = candidate.substring(normalisedEndpoint.length() + 1);
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new IllegalArgumentException("Image reference names no object: " + url);
        }

        String bucket = path.substring(0, slash);
        String key = path.substring(slash + 1);

        if (!List.of(allowedBuckets).contains(bucket)) {
            throw new IllegalArgumentException("Image reference names an unknown bucket: " + bucket);
        }
        // "..%2f" and friends never appear in keys this system writes, and a traversal
        // attempt is a signal worth refusing rather than normalising.
        if (key.contains("..")) {
            throw new IllegalArgumentException("Image reference contains a traversal segment");
        }

        return new ObjectRef(bucket, key);
    }

    private static String trimSlash(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
