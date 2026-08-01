package com.mercala.imagegen.storage;

/**
 * Image formats the pipeline can receive, identified by magic bytes.
 *
 * <p>Providers do not agree on output format — Replicate's flux models default to WebP,
 * Pollinations returns JPEG, OpenAI-compatible endpoints return PNG. Storing all of them
 * under a {@code .png} name with an {@code image/png} content type would leave browsers
 * decoding a file whose declared type is a lie, which some refuse to render.
 */
public enum ImageFormat {

    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif");

    private final String extension;
    private final String contentType;

    ImageFormat(String extension, String contentType) {
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
     * Sniffs the format from the leading bytes, defaulting to PNG when nothing matches
     * so an unrecognised payload still stores rather than failing the request.
     */
    public static ImageFormat detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return PNG;
        }

        // \x89 P N G \r \n \x1a \n
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return PNG;
        }
        // \xFF \xD8 \xFF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        // "RIFF" .... "WEBP"
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return WEBP;
        }
        // "GIF8"
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return GIF;
        }
        return PNG;
    }
}
