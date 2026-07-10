package com.mercala.imagegen.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

@Component
public class OpenAiImageProviderAdapter implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageProviderAdapter.class);

    private final ImageModel imageModel;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @Autowired
    public OpenAiImageProviderAdapter(
            java.util.Optional<ImageModel> imageModel,
            java.util.Optional<CircuitBreakerRegistry> circuitBreakerRegistry,
            java.util.Optional<RetryRegistry> retryRegistry) {
        this.imageModel = imageModel.orElse(null);
        this.circuitBreakerRegistry = circuitBreakerRegistry.orElse(null);
        this.retryRegistry = retryRegistry.orElse(null);
    }

    @Override
    public byte[] generateImage(String prompt) {
        if (imageModel == null) {
            log.warn("ImageModel is not available. Falling back to keyless AI image generator.");
            return generateMockImage(prompt);
        }

        CircuitBreaker cb = circuitBreakerRegistry != null ? 
                circuitBreakerRegistry.circuitBreaker("openai-image") : null;
        Retry retry = retryRegistry != null ? 
                retryRegistry.retry("openai-image") : null;

        if (cb != null && !cb.tryAcquirePermission()) {
            log.warn("Circuit breaker is OPEN. Falling back to mock image.");
            return generateMockImage(prompt);
        }

        long start = System.nanoTime();
        try {
            byte[] result;
            if (retry != null) {
                result = retry.executeSupplier(() -> callOpenAiImageModel(prompt));
            } else {
                result = callOpenAiImageModel(prompt);
            }
            if (cb != null) {
                cb.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            return result;
        } catch (Exception e) {
            if (cb != null) {
                cb.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, e);
            }
            log.error("Failed to generate image via OpenAI ImageModel. Falling back to mock PNG. Error: {}", e.getMessage());
            return generateMockImage(prompt);
        }
    }

    private byte[] callOpenAiImageModel(String prompt) {
        log.info("Generating image with OpenAI ImageModel for prompt: '{}'", prompt);
        
        OpenAiImageOptions options = OpenAiImageOptions.builder()
                .withResponseFormat("b64_json")
                .withN(1)
                .withHeight(1024)
                .withWidth(1024)
                .build();

        ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));
        
        if (response == null || response.getResults().isEmpty()) {
            throw new RuntimeException("Empty response from ImageModel");
        }

        String b64 = response.getResult().getOutput().getB64Json();
        if (b64 != null && !b64.isBlank()) {
            return Base64.getDecoder().decode(b64.trim());
        }

        // Fallback: try reading from URL
        String url = response.getResult().getOutput().getUrl();
        if (url != null && !url.isBlank()) {
            log.info("ImageModel returned URL. Downloading image from: {}", url);
            try (var inputStream = URI.create(url).toURL().openStream()) {
                return inputStream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Failed to download image from URL: " + url, e);
            }
        }

        throw new RuntimeException("Neither b64_json nor url was returned by ImageModel");
    }

    private byte[] generateMockImage(String prompt) {
        log.info("Attempting to fetch a free AI image from Pollinations.ai for prompt: '{}'", prompt);
        try {
            String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            String urlStr = "https://image.pollinations.ai/prompt/" + encodedPrompt + "?width=1024&height=1024&nologo=true&private=true";

            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);

            int status = conn.getResponseCode();
            if (status == 200) {
                try (var is = conn.getInputStream()) {
                    byte[] bytes = is.readAllBytes();
                    log.info("Successfully fetched AI image from Pollinations.ai (size: {} bytes)", bytes.length);
                    return bytes;
                }
            } else {
                log.warn("Pollinations.ai returned status {}. Falling back to canvas drawing.", status);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch image from Pollinations.ai. Error: {}. Falling back to canvas drawing.", e.getMessage());
        }

        return drawPlaceholderImage(prompt);
    }

    private byte[] drawPlaceholderImage(String prompt) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);

            var g = img.createGraphics();
            int hashCode = prompt != null ? prompt.hashCode() : 0;
            java.util.Random r = new java.util.Random(hashCode);
            g.setColor(new java.awt.Color(r.nextInt(150) + 30, r.nextInt(150) + 30, r.nextInt(150) + 30));
            g.fillRect(0, 0, 512, 512);

            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 24));

            String text = prompt != null && prompt.length() > 30 ? prompt.substring(0, 27) + "..." : prompt;
            g.drawString(text != null ? text : "MOCK IMAGE", 40, 256);
            g.dispose();

            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate mock PNG bytes", e);
            return new byte[0];
        }
    }
}
