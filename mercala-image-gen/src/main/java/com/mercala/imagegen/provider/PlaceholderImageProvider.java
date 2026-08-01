package com.mercala.imagegen.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Draws a deterministic placeholder locally. No network, no credentials, cannot fail
 * in any way the caller needs to handle — which is exactly why it anchors the end of
 * the fallback chain.
 *
 * <p>Colour is derived from the prompt hash, so the same product always gets the same
 * placeholder instead of flickering between deploys.
 */
@Component
public class PlaceholderImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderImageProvider.class);
    private static final int SIZE = 512;

    @Override
    public String name() {
        return "placeholder";
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public byte[] generateImage(String prompt) {
        log.info("Drawing local placeholder image for prompt: '{}'", prompt);
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Random random = new Random(prompt != null ? prompt.hashCode() : 0);
            g.setColor(new Color(random.nextInt(150) + 30, random.nextInt(150) + 30, random.nextInt(150) + 30));
            g.fillRect(0, 0, SIZE, SIZE);

            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 24));
            g.drawString(truncate(prompt), 40, SIZE / 2);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            // Encoding a fixed-size in-memory RGB image cannot realistically fail. If it
            // somehow does, there is no further fallback, so surface it rather than
            // returning empty bytes the consumer would reject anyway.
            throw new IllegalStateException("Failed to encode placeholder PNG", e);
        }
    }

    private static String truncate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "MERCALA";
        }
        return prompt.length() > 30 ? prompt.substring(0, 27) + "..." : prompt;
    }
}
