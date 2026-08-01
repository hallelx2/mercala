package com.mercala.imagegen.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercala.imagegen.storage.ImageFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real Cloudflare Workers AI API.
 *
 * <p>Opt-in: skipped unless {@code CLOUDFLARE_API_TOKEN} and {@code CLOUDFLARE_ACCOUNT_ID}
 * are present in the environment, so CI stays hermetic and no credentials are required
 * to build. Run locally with those set to confirm the integration end to end after a
 * config or model change.
 *
 * <p>Consumes free-tier allowance — one image per run.
 */
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_API_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_ACCOUNT_ID", matches = ".+")
class CloudflareImageProviderLiveTest {

    private CloudflareImageProvider provider() {
        CloudflareProperties props = new CloudflareProperties();
        props.setAccountId(System.getenv("CLOUDFLARE_ACCOUNT_ID"));
        props.setApiToken(System.getenv("CLOUDFLARE_API_TOKEN"));
        props.setModel(envOrDefault("CLOUDFLARE_IMAGE_MODEL", "@cf/black-forest-labs/flux-1-schnell"));
        props.setTimeout(Duration.ofSeconds(90));
        props.getInput().put("steps", envOrDefault("CLOUDFLARE_STEPS", "4"));
        return new CloudflareImageProvider(new ObjectMapper(), props);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Deliberately one test doing one generation, not two tests doing two. Every
     * assertion here is about the same response, and each extra test method would spend
     * another image from the daily free allowance for no additional coverage.
     */
    @Test
    void generatesARealImageAndItsFormatIsDetectedFromTheBytes() {
        byte[] bytes = provider().generateImage("studio product photo of a red leather sneaker on a white background");

        assertThat(bytes).isNotNull().isNotEmpty();
        assertThat(bytes.length)
                .as("a real generated image should be substantially larger than an error payload")
                .isGreaterThan(10_000);

        ImageFormat format = ImageFormat.detect(bytes);

        // flux-1-schnell returns JPEG despite the base64 field being described only as
        // "image", and there is no output_format input to request otherwise. Storing it
        // as .png/image/png — as the pipeline did before HAL-423 — would produce a file
        // whose declared type contradicts its bytes.
        assertThat(format)
                .as("format must be sniffed, not assumed")
                .isIn(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP);
        assertThat(format.contentType()).startsWith("image/");
    }

    @Test
    void reportsAvailableWithRealCredentials() {
        // No generation call: availability is a credential check, so this costs nothing.
        assertThat(provider().isAvailable()).isTrue();
    }
}
