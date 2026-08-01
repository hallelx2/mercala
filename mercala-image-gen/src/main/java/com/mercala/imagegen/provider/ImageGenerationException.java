package com.mercala.imagegen.provider;

/**
 * Thrown by an {@link ImageProvider} that could not produce an image. Signals the
 * router to move to the next provider in the fallback chain.
 */
public class ImageGenerationException extends RuntimeException {

    public ImageGenerationException(String message) {
        super(message);
    }

    public ImageGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
