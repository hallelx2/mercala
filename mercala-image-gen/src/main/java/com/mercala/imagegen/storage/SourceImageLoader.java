package com.mercala.imagegen.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches the merchant's original photograph so a provider can retouch it.
 *
 * <h2>Why this is not just an HTTP GET</h2>
 *
 * <p>The URL arrives on a Kafka event that originated from a chat message. Following it
 * unconditionally would turn this worker into an SSRF proxy: the event could name
 * {@code http://169.254.169.254/latest/meta-data/iam/...} and this service, which runs on a
 * host with an instance profile, would fetch it and hand the bytes to an external image
 * provider. So the host must be one this deployment already trusts to hold images —
 * everything else is refused before a connection is opened.
 *
 * <p>The size cap exists for a smaller reason with the same shape: a provider request is
 * built in memory, and an unbounded download decides how much memory that is.
 */
@Component
public class SourceImageLoader {

    private static final Logger log = LoggerFactory.getLogger(SourceImageLoader.class);

    private final HttpClient httpClient;
    private final List<String> allowedPrefixes;
    private final long maxBytes;

    public SourceImageLoader(
            @Value("${mercala.storage.endpoint:}") String storageEndpoint,
            @Value("${mercala.storage.public-base-url:}") String publicBaseUrl,
            @Value("${mercala.image-gen.source.max-bytes:15728640}") long maxBytes) {
        this.maxBytes = maxBytes;
        this.allowedPrefixes = Arrays.stream(new String[]{storageEndpoint, publicBaseUrl})
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .map(value -> value.endsWith("/") ? value.substring(0, value.length() - 1) : value)
                .toList();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Redirects are not followed: an allowed host that answers with a 302 to a
                // metadata endpoint would otherwise walk straight past the check above.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public byte[] load(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("An enhancement needs a source image URL");
        }
        requireAllowed(url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not fetch the source image from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching the source image", e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Fetching the source image returned status " + response.statusCode());
        }

        byte[] bytes = response.body();
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

    /**
     * With no storage endpoint configured there is no trusted host to compare against, so
     * nothing is fetched. Failing closed here costs a misconfigured deployment its
     * enhancement feature; failing open costs it its instance credentials.
     */
    private void requireAllowed(String url) {
        String candidate = url.toLowerCase(Locale.ROOT);
        if (allowedPrefixes.isEmpty()) {
            throw new IllegalStateException(
                    "No storage endpoint is configured, so no source image host is trusted");
        }
        boolean allowed = allowedPrefixes.stream().anyMatch(candidate::startsWith);
        if (!allowed) {
            log.warn("Refusing to fetch a source image from an untrusted host: {}", url);
            throw new IllegalArgumentException(
                    "Source images must come from Mercala storage, not from an arbitrary URL");
        }
    }
}
