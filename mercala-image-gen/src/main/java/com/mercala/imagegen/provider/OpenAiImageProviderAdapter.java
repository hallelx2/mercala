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
            log.warn("ImageModel is not available. Falling back to generating a mock PNG image.");
            return generateMockImage();
        }

        CircuitBreaker cb = circuitBreakerRegistry != null ? 
                circuitBreakerRegistry.circuitBreaker("openai-image") : null;
        Retry retry = retryRegistry != null ? 
                retryRegistry.retry("openai-image") : null;

        if (cb != null && !cb.tryAcquirePermission()) {
            log.warn("Circuit breaker is OPEN. Falling back to mock image.");
            return generateMockImage();
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
            return generateMockImage();
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

    private byte[] generateMockImage() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            
            var g = img.createGraphics();
            g.setColor(new java.awt.Color(34, 139, 34)); // Forest green
            g.fillRect(0, 0, 128, 128);
            g.setColor(java.awt.Color.WHITE);
            g.drawString("MOCK IMAGE", 10, 64);
            g.dispose();

            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate mock PNG bytes", e);
            return new byte[0];
        }
    }
}
