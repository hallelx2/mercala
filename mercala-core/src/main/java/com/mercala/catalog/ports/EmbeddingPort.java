package com.mercala.catalog.ports;

/**
 * Port representing the downstream embedding generation service.
 */
public interface EmbeddingPort {

    /**
     * Generates a 1536-dimensional embedding vector for the given text payload.
     *
     * @param text the input text to embed
     * @return the float array representing the embedding vector
     */
    float[] getEmbedding(String text);
}
