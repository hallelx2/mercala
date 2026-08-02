package com.mercala.imagegen.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.Map;

/**
 * Image generation via Replicate.
 *
 * <p>Replicate runs predictions asynchronously, but supports a {@code Prefer: wait}
 * header that holds the connection open until the prediction finishes or the wait budget
 * expires. The happy path is therefore a single request; polling covers models slower
 * than the budget so a cold start does not fail the request.
 *
 * <p>Model inputs come from {@link ReplicateProperties#getInput()} rather than being
 * hardcoded, because Replicate models do not share an input schema.
 */
@Component
public class ReplicateImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(ReplicateImageProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReplicateProperties properties;
    private final String baseUrl;

    public ReplicateImageProvider(ObjectMapper objectMapper, ReplicateProperties properties) {
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
        return "replicate";
    }

    /**
     * Without a token every call would 401. Reporting unavailable lets the router skip
     * straight to the next provider instead of burning a request and a circuit-breaker
     * failure on a misconfiguration.
     */
    @Override
    public boolean isAvailable() {
        boolean available = properties.getApiToken() != null && !properties.getApiToken().isBlank();
        if (!available) {
            log.debug("Replicate provider unavailable: no API token configured");
        }
        return available;
    }

    @Override
    public byte[] generateImage(String prompt) {
        if (!isAvailable()) {
            throw new ImageGenerationException("Replicate API token is not configured");
        }

        log.info("Requesting image from Replicate model={} for prompt: '{}'", properties.getModel(), prompt);
        ObjectNode input = objectMapper.createObjectNode();
        input.put("prompt", prompt == null ? "" : prompt);
        applyConfigured(input, properties.getInput());

        return await(createPrediction(properties.getModel(), input));
    }

    @Override
    public boolean supportsEnhancement() {
        return properties.getEnhanceModel() != null && !properties.getEnhanceModel().isBlank();
    }

    /**
     * Replicate takes the source image as a URI, and the merchant's photo is in a bucket
     * that shoppers cannot read (HAL-425) — so handing over a storage URL would give
     * Replicate a 403 rather than a picture. A data URI carries the bytes in the request
     * instead, which needs no public object and no presigned URL machinery.
     */
    @Override
    public byte[] enhanceImage(byte[] sourceImage, String instruction, double strength) {
        if (!isAvailable()) {
            throw new ImageGenerationException("Replicate API token is not configured");
        }
        if (sourceImage == null || sourceImage.length == 0) {
            throw new ImageGenerationException("Cannot enhance an empty source image");
        }

        String model = properties.getEnhanceModel();
        log.info("Enhancing a {} byte image with Replicate model={} at strength {}: '{}'",
                sourceImage.length, model, strength, instruction);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("prompt", instruction == null ? "" : instruction);
        input.put(properties.getEnhanceImageField(), dataUri(sourceImage));
        // Denoising-strength models read this; instruction-edit models ignore it. Sending
        // it unconditionally is cheaper than teaching this class which family it is talking to.
        input.put("prompt_strength", strength);
        applyConfigured(input, properties.getEnhanceInput());

        return await(createPrediction(model, input));
    }

    /** Create, poll if needed, and download — shared by both modes. */
    private byte[] await(JsonNode created) {
        JsonNode prediction = created;
        String status = prediction.path("status").asText("");
        if (!isTerminal(status)) {
            prediction = pollUntilTerminal(prediction.path("id").asText());
            status = prediction.path("status").asText("");
        }

        if (!"succeeded".equals(status)) {
            String error = prediction.path("error").asText("");
            throw new ImageGenerationException(
                    "Replicate prediction ended with status '" + status + "'"
                            + (error.isBlank() ? "" : ": " + error));
        }

        return download(extractOutputUrl(prediction));
    }

    private static String dataUri(byte[] image) {
        return "data:" + com.mercala.imagegen.storage.ImageFormat.detect(image).contentType()
                + ";base64," + java.util.Base64.getEncoder().encodeToString(image);
    }

    /**
     * Configured inputs are typed by value so numbers and booleans reach Replicate as JSON
     * numbers and booleans rather than quoted strings, which some models reject.
     */
    private static void applyConfigured(ObjectNode input, Map<String, String> configured) {
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            putTyped(input, entry.getKey(), entry.getValue());
        }
    }

    private JsonNode createPrediction(String model, ObjectNode input) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("input", input);

        long waitSeconds = properties.getWait().toSeconds();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + model + "/predictions"))
                .timeout(properties.getWait().plusSeconds(15))
                .header("Authorization", "Bearer " + properties.getApiToken())
                .header("Content-Type", "application/json")
                // Hold the connection open until the prediction resolves, so the common
                // case needs no polling at all.
                .header("Prefer", "wait=" + waitSeconds)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(),
                "create Replicate prediction");

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new ImageGenerationException(
                    "Replicate returned status " + response.statusCode() + ": " + truncate(response.body()));
        }
        return parse(response.body());
    }

    /**
     * Config values arrive as strings. Replicate's schemas are typed, so coerce the
     * obvious cases and pass anything else through untouched.
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
        try {
            if (trimmed.matches("-?\\d+")) {
                node.put(key, Long.parseLong(trimmed));
                return;
            }
            if (trimmed.matches("-?\\d*\\.\\d+")) {
                node.put(key, Double.parseDouble(trimmed));
                return;
            }
        } catch (NumberFormatException ignored) {
            // Fall through and send it as a string.
        }
        node.put(key, trimmed);
    }

    private JsonNode pollUntilTerminal(String predictionId) {
        if (predictionId == null || predictionId.isBlank()) {
            throw new ImageGenerationException("Replicate response contained no prediction id to poll");
        }

        Instant deadline = Instant.now().plus(properties.getPollTimeout());
        log.info("Replicate prediction {} still running; polling for up to {}s",
                predictionId, properties.getPollTimeout().toSeconds());

        while (Instant.now().isBefore(deadline)) {
            sleep(properties.getPollInterval());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/predictions/" + predictionId))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .GET()
                    .build();

            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(),
                    "poll Replicate prediction");

            if (response.statusCode() != 200) {
                throw new ImageGenerationException(
                        "Replicate poll returned status " + response.statusCode() + ": " + truncate(response.body()));
            }

            JsonNode prediction = parse(response.body());
            if (isTerminal(prediction.path("status").asText(""))) {
                return prediction;
            }
        }

        throw new ImageGenerationException("Replicate prediction " + predictionId
                + " did not finish within " + properties.getPollTimeout().toSeconds() + "s");
    }

    /**
     * Models differ: some return a bare URL string, others an array of them. Handle both
     * rather than assuming the shape of whichever model is configured today.
     */
    private String extractOutputUrl(JsonNode prediction) {
        JsonNode output = prediction.path("output");

        if (output.isTextual()) {
            return output.asText();
        }
        if (output.isArray() && !output.isEmpty()) {
            JsonNode first = output.get(0);
            if (first.isTextual()) {
                return first.asText();
            }
        }
        throw new ImageGenerationException("Replicate prediction succeeded but returned no usable output URL");
    }

    private byte[] download(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray(),
                "download Replicate output");

        if (response.statusCode() != 200) {
            throw new ImageGenerationException("Downloading Replicate output returned status " + response.statusCode());
        }

        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            throw new ImageGenerationException("Replicate output URL returned an empty body");
        }

        log.info("Replicate returned {} bytes from model {}", bytes.length, properties.getModel());
        return bytes;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, String action) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            throw new ImageGenerationException("Failed to " + action, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("Interrupted trying to " + action, e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ImageGenerationException("Could not parse Replicate response: " + truncate(body), e);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("Interrupted while polling Replicate", e);
        }
    }

    private static boolean isTerminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
