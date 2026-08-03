package com.mercala.catalog.web.dto;

/**
 * A product's picture, as a client needs it.
 *
 * <p>Two URLs, because they answer different questions. {@code url} is where the object
 * lives: stable across requests, and the thing to compare when deciding whether a render
 * that just arrived is the one already on screen. {@code viewUrl} is that object signed,
 * and is the only one that loads — the bucket is private and holds rather more than
 * pictures (HAL-425).
 *
 * <p>{@code viewUrl} must never be stored or used as an identity. It expires, and two reads
 * of the same image produce two different signatures.
 *
 * @param viewUrl null when storage could not be reached to sign it, which costs the shopper
 *                a picture rather than the page
 */
public record ProductImageView(String url, String viewUrl) {
}
