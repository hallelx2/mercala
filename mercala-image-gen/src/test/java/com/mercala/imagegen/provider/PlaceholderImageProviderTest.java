package com.mercala.imagegen.provider;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderImageProviderTest {

    private final PlaceholderImageProvider provider = new PlaceholderImageProvider();

    @Test
    void producesADecodablePng() throws IOException {
        byte[] bytes = provider.generateImage("a red shoe");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("output must be a real PNG, not arbitrary bytes").isNotNull();
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).isEqualTo(512);
    }

    @Test
    void isDeterministicForTheSamePrompt() {
        assertThat(provider.generateImage("a red shoe"))
                .as("the same product must not change appearance between deploys")
                .isEqualTo(provider.generateImage("a red shoe"));
    }

    @Test
    void differsBetweenPrompts() {
        assertThat(provider.generateImage("a red shoe"))
                .isNotEqualTo(provider.generateImage("a blue hat"));
    }

    @Test
    void handlesNullAndOverlongPrompts() throws IOException {
        assertThat(ImageIO.read(new ByteArrayInputStream(provider.generateImage(null)))).isNotNull();

        String longPrompt = "a ".repeat(200) + "shoe";
        assertThat(ImageIO.read(new ByteArrayInputStream(provider.generateImage(longPrompt)))).isNotNull();
    }

    @Test
    void isLocalSoTheRouterDoesNotWrapItInACircuitBreaker() {
        assertThat(provider.isRemote()).isFalse();
        assertThat(provider.isAvailable()).isTrue();
    }
}
