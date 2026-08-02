package com.mercala.contracts.event;

/**
 * What kind of image work a request asks for.
 *
 * <p>Two names rather than a boolean, because a third is already foreseeable — upscaling,
 * background removal as its own operation — and {@code enhance=false} would be a poor way
 * to say "upscale".
 */
public enum ImageMode {

    /** Invent an image from a text prompt. */
    GENERATE,

    /** Retouch an image the merchant supplied, guided by an instruction. */
    ENHANCE
}
