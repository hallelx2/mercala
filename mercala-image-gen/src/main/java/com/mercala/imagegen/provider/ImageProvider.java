package com.mercala.imagegen.provider;

/**
 * Port for generating product images from prompts.
 *
 * <p>Each implementation talks to exactly one backend. Selection between them, and
 * falling back when one fails, is {@link ImageProviderRouter}'s job — never an
 * individual provider's. A provider that cannot do its work throws; it does not
 * quietly substitute a different backend's result.
 */
public interface ImageProvider {

    /**
     * Stable identifier used to select this provider via
     * {@code mercala.image-gen.provider} and in the fallback chain. Lowercase, no spaces.
     */
    String name();

    /**
     * Generates an image for the given prompt.
     *
     * @param prompt descriptive text prompt
     * @return raw image bytes, never empty
     * @throws RuntimeException if this provider could not produce an image
     */
    byte[] generateImage(String prompt);

    /**
     * Whether this provider is configured well enough to be worth calling. The router
     * skips unavailable providers rather than counting them as failures — a provider
     * with no API key set is not an outage.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Whether this provider can retouch an image the merchant supplied, rather than only
     * inventing one from text.
     *
     * <p>Opt-in, and false by default, because the capability is genuinely uneven: some
     * backends host no image-to-image model at all. The router reads this to build the
     * enhancement chain, so a provider that cannot do it is skipped rather than called and
     * allowed to throw — the difference between "not offered here" and "this backend is
     * failing", which is what the circuit breaker would otherwise record.
     */
    default boolean supportsEnhancement() {
        return false;
    }

    /**
     * Retouches an image the merchant already has.
     *
     * <p>This is the operation a store owner actually wants most of the time: the photo
     * exists, it was taken on a phone, and it needs to look like a catalogue shot.
     *
     * @param sourceImage the merchant's original bytes
     * @param instruction what to change — "remove the background, studio lighting"
     * @param strength    0.0 keeps the original, 1.0 abandons it; useful range is 0.2–0.6
     * @return raw image bytes, never empty
     * @throws UnsupportedOperationException if this provider does not do image-to-image
     * @throws RuntimeException if it does, but could not
     */
    default byte[] enhanceImage(byte[] sourceImage, String instruction, double strength) {
        throw new UnsupportedOperationException(name() + " does not support image enhancement");
    }

    /**
     * Whether calls to this provider cross the network. The router applies circuit
     * breaking and retries only to remote providers — wrapping a purely local one would
     * add a failure mode rather than protect against one.
     */
    default boolean isRemote() {
        return true;
    }
}
