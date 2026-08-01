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

    @Test
    void generatesARealImage() {
        byte[] bytes = provider().generateImage("studio product photo of a red leather sneaker on a white background");

        assertThat(bytes).isNotNull().isNotEmpty();
        assertThat(bytes.length)
                .as("a real generated image should be substantially larger than an error payload")
                .isGreaterThan(10_000);
    }

    @Test
    void detectedFormatMatchesWhatCloudflareActuallyReturns() {
        byte[] bytes = provider().generateImage("a plain blue square");

        ImageFormat format = ImageFormat.detect(bytes);

        // flux-1-schnell returns JPEG despite the base64 field being described only as
        // "image". Storing it as .png/image/png — as the pipeline did before HAL-423 —
        // would produce a file whose declared type contradicts its bytes.
        assertThat(format)
                .as("format must be sniffed, not assumed")
                .isIn(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP);
        assertThat(format.contentType()).startsWith("image/");
    }

    @Test
    void reportsAvailableWithRealCredentials() {
        assertThat(provider().isAvailable()).isTrue();
    }
}
