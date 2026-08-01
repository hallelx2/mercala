package com.mercala.imagegen.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ImageFormatTest {

    @Test
    void detectsPng() {
        byte[] png = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, new byte[8]);
        assertThat(ImageFormat.detect(png)).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void detectsJpeg() {
        byte[] jpeg = concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, new byte[8]);
        assertThat(ImageFormat.detect(jpeg)).isEqualTo(ImageFormat.JPEG);
    }

    @Test
    void detectsWebpWhichIsWhatReplicateFluxReturnsByDefault() {
        byte[] webp = concat(
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0},
                new byte[]{'W', 'E', 'B', 'P'});
        assertThat(ImageFormat.detect(webp)).isEqualTo(ImageFormat.WEBP);
        assertThat(ImageFormat.WEBP.extension()).isEqualTo("webp");
        assertThat(ImageFormat.WEBP.contentType()).isEqualTo("image/webp");
    }

    @Test
    void detectsGif() {
        byte[] gif = concat(new byte[]{'G', 'I', 'F', '8', '9', 'a'}, new byte[8]);
        assertThat(ImageFormat.detect(gif)).isEqualTo(ImageFormat.GIF);
    }

    @Test
    void fallsBackToPngForUnrecognisedOrShortInput() {
        assertThat(ImageFormat.detect(null)).isEqualTo(ImageFormat.PNG);
        assertThat(ImageFormat.detect(new byte[0])).isEqualTo(ImageFormat.PNG);
        assertThat(ImageFormat.detect(new byte[]{1, 2, 3})).isEqualTo(ImageFormat.PNG);
        assertThat(ImageFormat.detect(new byte[20])).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void extensionsAndContentTypesAgree() {
        assertThat(ImageFormat.PNG.contentType()).isEqualTo("image/png");
        assertThat(ImageFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(ImageFormat.JPEG.contentType()).isEqualTo("image/jpeg");
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head);
        out.writeBytes(tail);
        return out.toByteArray();
    }
}
