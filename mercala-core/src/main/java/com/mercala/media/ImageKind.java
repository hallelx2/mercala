package com.mercala.media;

/**
 * The image formats a merchant may upload, identified by the bytes rather than the name.
 *
 * <p>An upload's declared content type and its file extension are both attacker-controlled:
 * anything can be called {@code photo.png} and sent as {@code image/png}. The leading bytes
 * are the only part of the claim that has to be true for the file to actually be an image,
 * so they are what decides. Anything unrecognised is refused rather than stored under a
 * guess — this bucket's contents are served back to browsers.
 */
public enum ImageKind {

    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif");

    private final String extension;
    private final String contentType;

    ImageKind(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * @return the format, or null when the bytes are not one this accepts
     */
    public static ImageKind detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return PNG;
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return WEBP;
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return GIF;
        }
        return null;
    }
}
