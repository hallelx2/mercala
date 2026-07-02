package com.mercala.imagegen.provider;

/**
 * Port interface for generating product images from prompts.
 */
public interface ImageProvider {

    /**
     * Generates an image based on the given prompt.
     *
     * @param prompt The descriptive text prompt for generating the image
     * @return The raw image bytes
     */
    byte[] generateImage(String prompt);
}
