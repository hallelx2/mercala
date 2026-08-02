package com.mercala.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What decides whether an upload is an image.
 *
 * <p>Filename and declared content type are both chosen by whoever is uploading, so neither
 * can be the answer. These objects are served back to browsers; storing something that is
 * not an image under an image content type is how a bucket becomes a delivery mechanism.
 */
class ImageKindTest {

    private static byte[] withHeader(int... header) {
        byte[] bytes = new byte[64];
        for (int i = 0; i < header.length; i++) {
            bytes[i] = (byte) header[i];
        }
        return bytes;
    }

    @Test
    void theFourAcceptedFormatsAreRecognisedByTheirLeadingBytes() {
        assertThat(ImageKind.detect(withHeader(0x89, 'P', 'N', 'G'))).isEqualTo(ImageKind.PNG);
        assertThat(ImageKind.detect(withHeader(0xFF, 0xD8, 0xFF))).isEqualTo(ImageKind.JPEG);
        assertThat(ImageKind.detect(withHeader('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')))
                .isEqualTo(ImageKind.WEBP);
        assertThat(ImageKind.detect(withHeader('G', 'I', 'F', '8'))).isEqualTo(ImageKind.GIF);
    }

    /**
     * The case the controller turns into a 415: a Windows executable renamed to
     * {@code photo.png} and declared as {@code image/png}.
     */
    @Test
    void anExecutableWearingAnImageNameIsNotAnImage() {
        assertThat(ImageKind.detect(withHeader('M', 'Z', 0x90, 0x00))).isNull();
    }

    @Test
    void arbitraryTextIsNotAnImage() {
        assertThat(ImageKind.detect("this is definitely a photograph, honestly".getBytes())).isNull();
    }

    /**
     * A truncated upload cannot be identified, and guessing PNG would store a broken file
     * under a content type that promises it works.
     */
    @Test
    void tooFewBytesToIdentifyIsNotAGuess() {
        assertThat(ImageKind.detect(new byte[]{(byte) 0x89, 'P'})).isNull();
        assertThat(ImageKind.detect(new byte[0])).isNull();
        assertThat(ImageKind.detect(null)).isNull();
    }

    /** A RIFF container that is not WebP — a WAV file, say — must not pass as one. */
    @Test
    void aRiffContainerThatIsNotWebpIsRejected() {
        assertThat(ImageKind.detect(withHeader('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'))).isNull();
    }

    @Test
    void everyKindKnowsItsExtensionAndContentType() {
        assertThat(ImageKind.JPEG.extension()).isEqualTo("jpg");
        assertThat(ImageKind.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageKind.WEBP.contentType()).isEqualTo("image/webp");
    }
}
