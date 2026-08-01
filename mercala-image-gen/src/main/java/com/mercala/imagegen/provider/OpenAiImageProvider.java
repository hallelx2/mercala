package com.mercala.imagegen.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    public OpenAiImageProvider(Optional<ImageModel> imageModel) {
        this.imageModel = imageModel.orElse(null);
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
