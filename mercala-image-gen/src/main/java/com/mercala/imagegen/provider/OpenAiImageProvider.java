package com.mercala.imagegen.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercala.imagegen.storage.ImageFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Image generation through any OpenAI-compatible image endpoint, via Spring AI.
 *
 * <p>The concrete model and base URL come from {@code spring.ai.openai.*}, so this also
 * covers compatible providers such as CogView through a proxied base URL.
 *
 * <p>Unlike its predecessor this class does one thing: call the model. It has no
 * knowledge of Pollinations, placeholders, or fallback ordering.
 */
@Component
public class OpenAiImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageProvider.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final ImageModel imageModel;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String editModel;

    public OpenAiImageProvider(
            Optional<ImageModel> imageModel,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${mercala.image-gen.openai.edit-model:gpt-image-1}") String editModel) {
        this.imageModel = imageModel.orElse(null);
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.editModel = editModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return imageModel != null;
    }

    @Override
    public byte[] generateImage(String prompt) {
        if (imageModel == null) {
            throw new ImageGenerationException("No OpenAI-compatible ImageModel is configured");
        }

        log.info("Requesting image from OpenAI-compatible ImageModel for prompt: '{}'", prompt);

        OpenAiImageOptions options = OpenAiImageOptions.builder()
                .withResponseFormat("b64_json")
                .withN(1)
                .withHeight(1024)
                .withWidth(1024)
                .build();

        ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));
        if (response == null || response.getResults().isEmpty()) {
            throw new ImageGenerationException("ImageModel returned an empty response");
        }

        String b64 = response.getResult().getOutput().getB64Json();
        if (b64 != null && !b64.isBlank()) {
            return Base64.getDecoder().decode(b64.trim());
        }

        String url = response.getResult().getOutput().getUrl();
        if (url != null && !url.isBlank()) {
            log.info("ImageModel returned a URL; downloading from {}", url);
            return download(url);
        }

        throw new ImageGenerationException("ImageModel returned neither b64_json nor url");
    }

    /**
     * The edits endpoint is a different call from the one Spring AI wraps — it is
     * multipart, not JSON — so it needs the key and base URL directly rather than through
     * the {@code ImageModel}. Without a key there is nothing to call.
     */
    @Override
    public boolean supportsEnhancement() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Image editing through {@code POST /v1/images/edits}.
     *
     * <p>Hand-rolled multipart rather than a client library: this module has no HTTP client
     * dependency beyond the JDK's, and adding one to send three form parts would be a
     * larger change than writing the three form parts.
     */
    @Override
    public byte[] enhanceImage(byte[] sourceImage, String instruction, double strength) {
        if (!supportsEnhancement()) {
            throw new ImageGenerationException("No OpenAI API key is configured for image editing");
        }
        if (sourceImage == null || sourceImage.length == 0) {
            throw new ImageGenerationException("Cannot enhance an empty source image");
        }

        log.info("Editing a {} byte image with OpenAI model={}: '{}'", sourceImage.length, editModel, instruction);

        String boundary = "mercala-" + java.util.UUID.randomUUID();
        byte[] body = multipartBody(boundary, sourceImage, instruction);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/images/edits"))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ImageGenerationException("Failed to call the OpenAI image edits endpoint", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("Interrupted calling the OpenAI image edits endpoint", e);
        }

        if (response.statusCode() != 200) {
            throw new ImageGenerationException("OpenAI image edits returned status " + response.statusCode()
                    + ": " + truncate(response.body()));
        }

        return extractEdited(response.body());
    }

    private byte[] multipartBody(String boundary, byte[] sourceImage, String instruction) {
        ImageFormat format = ImageFormat.detect(sourceImage);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            writePart(out, boundary, "model", editModel);
            writePart(out, boundary, "prompt", instruction == null ? "" : instruction);

            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"image\"; filename=\"source."
                    + format.extension() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + format.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(sourceImage);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ImageGenerationException("Could not assemble the image edit request", e);
        }

        return out.toByteArray();
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code gpt-image-1} always answers with base64; {@code dall-e-2} answers with a URL
     * unless asked otherwise. Both shapes are handled so changing the model stays a config
     * change.
     */
    private byte[] extractEdited(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ImageGenerationException("Could not parse the OpenAI edit response: " + truncate(body), e);
        }

        JsonNode first = root.path("data").path(0);
        String b64 = first.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return Base64.getDecoder().decode(b64.trim());
        }

        String url = first.path("url").asText("");
        if (!url.isBlank()) {
            return download(url);
        }

        throw new ImageGenerationException("OpenAI edit response contained neither b64_json nor url");
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    /**
     * {@code URL.openStream()} applies neither a connect nor a read timeout, so a stalled
     * image host would pin the consumer thread indefinitely. The router's circuit breaker
     * records the slow call but cannot interrupt it, so the bound has to live here.
     */
    private byte[] download(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(READ_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ImageGenerationException("Failed to download image from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenerationException("Interrupted downloading image from " + url, e);
        }

        if (response.statusCode() != 200) {
            throw new ImageGenerationException(
                    "Downloading image from " + url + " returned status " + response.statusCode());
        }

        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            throw new ImageGenerationException("ImageModel URL returned an empty body");
        }
        return bytes;
    }
}
