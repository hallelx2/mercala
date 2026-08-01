package com.mercala.imagegen.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Keyless generation via Pollinations.ai.
 *
 * <p>Requires no account or API token, which makes it useful for demos and as a
 * fallback when the primary provider is down or unconfigured. Quality and latency are
 * not guaranteed, so it is not the default in production.
 */
@Component
public class PollinationsImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(PollinationsImageProvider.class);
    private static final String BASE_URL = "https://image.pollinations.ai/prompt/";

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public PollinationsImageProvider(
            @Value("${mercala.image-gen.pollinations.timeout-seconds:20}") long timeoutSeconds) {
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "pollinations";
    }

    @Override
    public byte[] generateImage(String prompt) {
        String url = BASE_URL
                + URLEncoder.encode(prompt == null ? "" : prompt, StandardCharsets.UTF_8)
                + "?width=1024&height=1024&nologo=true&private=true";

        log.info("Requesting image from Pollinations.ai for prompt: '{}'", prompt);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ImageGenerationException(
                        "Pollinations.ai returned status " + response.statusCode());
            }

            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                throw new ImageGenerationException("Pollinations.ai returned an empty body");
            }

            log.info("Pollinations.ai returned {} bytes", bytes.length);
            return bytes;
        } catch (IOException e) {
            throw new ImageGenerationException("Pollinations.ai request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("Interrupted while calling Pollinations.ai", e);
        }
    }
}
