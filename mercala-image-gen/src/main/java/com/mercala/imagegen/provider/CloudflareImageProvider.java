package com.mercala.imagegen.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Image generation via Cloudflare Workers AI.
 *
 * <p>Synchronous, unlike Replicate: one request returns the finished image, so there is
 * no prediction id and nothing to poll.
 *
 * <p>Response handling covers both shapes Workers AI uses, because they differ by model
 * family and picking one would quietly break the other:
 * <ul>
 *   <li>{@code flux-1-schnell} returns the standard Cloudflare JSON envelope with the
 *       image base64-encoded under {@code result.image}</li>
 *   <li>the SDXL models return raw image bytes with an {@code image/*} content type</li>
 * </ul>
 * The content type decides which path is taken, so switching model needs no code change.
 */
@Component
public class CloudflareImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudflareImageProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CloudflareProperties properties;
    private final String baseUrl;

    public CloudflareImageProvider(ObjectMapper objectMapper, CloudflareProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;

        String configured = properties.getBaseUrl();
        this.baseUrl = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "cloudflare";
    }

    /**
     * Both an account id and a token are required — the account id is part of the URL
     * path, so a missing one produces a 404 rather than an auth error. Reporting
     * unavailable lets the router skip past a half-configured provider.
     */
    @Override
    public boolean isAvailable() {
        boolean available = hasText(properties.getAccountId()) && hasText(properties.getApiToken());
        if (!available) {
            log.debug("Cloudflare provider unavailable: account id or API token is not set");
        }
        return available;
    }

    @Override
    public byte[] generateImage(String prompt) {
        if (!isAvailable()) {
            throw new ImageGenerationException("Cloudflare account id or API token is not configured");
        }

        String model = properties.getModel();
        log.info("Requesting image from Cloudflare Workers AI model={} for prompt: '{}'", model, prompt);

        return run(model, buildBody(prompt));
    }

    /**
     * Workers AI hosts an image-to-image model on the same free allowance as generation,
     * which is what makes retouching a merchant's own photo cost nothing by default.
     */
    @Override
    public boolean supportsEnhancement() {
        return hasText(properties.getEnhanceModel());
    }

    @Override
    public byte[] enhanceImage(byte[] sourceImage, String instruction, double strength) {
        if (!isAvailable()) {
            throw new ImageGenerationException("Cloudflare account id or API token is not configured");
        }
        if (sourceImage == null || sourceImage.length == 0) {
            throw new ImageGenerationException("Cannot enhance an empty source image");
        }

        String model = properties.getEnhanceModel();
        log.info("Enhancing a {} byte image with Cloudflare Workers AI model={} at strength {}: '{}'",
                sourceImage.length, model, strength, instruction);

        return run(model, buildEnhanceBody(sourceImage, instruction, strength));
    }

    private byte[] run(String model, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/accounts/" + properties.getAccountId() + "/ai/run/" + model))
                .timeout(properties.getTimeout())
                .header("Authorization", "Bearer " + properties.getApiToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ImageGenerationException("Failed to call Cloudflare Workers AI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("Interrupted while calling Cloudflare Workers AI", e);
        }

        if (response.statusCode() != 200) {
            throw new ImageGenerationException("Cloudflare Workers AI returned status " + response.statusCode()
                    + ": " + describeError(response.body()));
        }

        return extractImage(response, model);
    }

    private String buildBody(String prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", truncatePrompt(prompt));

        for (Map.Entry<String, String> entry : properties.getInput().entrySet()) {
            putTyped(body, entry.getKey(), entry.getValue());
        }
        return body.toString();
    }

    /**
     * The img2img schema takes the source as {@code image}: an array of byte values, not
     * base64 and not multipart. It is a verbose encoding for a photograph, but it is the
     * one the model accepts, and sending base64 to it fails with a schema error rather
     * than a useful message.
     */
    private String buildEnhanceBody(byte[] sourceImage, String instruction, double strength) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", truncatePrompt(instruction));
        body.put("strength", strength);

        ArrayNode image = body.putArray("image");
        for (byte b : sourceImage) {
            image.add(b & 0xFF);
        }

        for (Map.Entry<String, String> entry : properties.getEnhanceInput().entrySet()) {
            putTyped(body, entry.getKey(), entry.getValue());
        }
        return body.toString();
    }

    /**
     * Workers AI rejects prompts over the model's limit. Product prompts are generated
     * upstream and can run long, so trim rather than fail.
     */
    private String truncatePrompt(String prompt) {
        String safe = prompt == null ? "" : prompt;
        int max = properties.getMaxPromptLength();
        if (max > 0 && safe.length() > max) {
            log.warn("Prompt of {} chars exceeds the Cloudflare limit of {}; truncating", safe.length(), max);
            return safe.substring(0, max);
        }
        return safe;
    }

    /**
     * Config values arrive as strings, but the model schemas are typed — {@code steps}
     * must be a JSON number, not "4".
     */
    private static void putTyped(ObjectNode node, String key, String value) {
        if (value == null) {
            node.putNull(key);
            return;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            node.put(key, Boolean.parseBoolean(trimmed));
            return;
        }
        if (trimmed.matches("-?\\d+")) {
            node.put(key, Long.parseLong(trimmed));
            return;
        }
        if (trimmed.matches("-?\\d*\\.\\d+")) {
            node.put(key, Double.parseDouble(trimmed));
            return;
        }
        node.put(key, trimmed);
    }

    private byte[] extractImage(HttpResponse<byte[]> response, String model) {
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();

        // SDXL and friends stream the image straight back.
        if (contentType.startsWith("image/")) {
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                throw new ImageGenerationException("Cloudflare returned an empty image body");
            }
            log.info("Cloudflare returned {} raw bytes from model {}", bytes.length, model);
            return bytes;
        }

        // flux-1-schnell returns the standard envelope with base64 under result.image.
        JsonNode root = parse(response.body());

        if (root.has("success") && !root.path("success").asBoolean(true)) {
            throw new ImageGenerationException("Cloudflare Workers AI reported failure: " + describeErrors(root));
        }

        JsonNode image = root.path("result").path("image");
        if (!image.isTextual() || image.asText().isBlank()) {
            throw new ImageGenerationException("Cloudflare response contained no result.image");
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(image.asText().trim());
            if (bytes.length == 0) {
                throw new ImageGenerationException("Cloudflare returned an empty image after base64 decoding");
            }
            log.info("Cloudflare returned {} bytes from model {}", bytes.length, model);
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new ImageGenerationException("Cloudflare result.image was not valid base64", e);
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return objectMapper.readTree(body == null ? new byte[0] : body);
        } catch (IOException e) {
            throw new ImageGenerationException("Could not parse Cloudflare response: " + describeError(body), e);
        }
    }

    /** Cloudflare reports problems as an {@code errors} array rather than an HTTP body string. */
    private String describeErrors(JsonNode root) {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode error : errors) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(error.path("code").asText("?")).append(": ").append(error.path("message").asText(""));
            }
            return sb.toString();
        }
        return "no error detail returned";
    }

    private String describeError(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
