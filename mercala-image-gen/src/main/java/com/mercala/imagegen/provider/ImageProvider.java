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
     * Whether calls to this provider cross the network. The router applies circuit
     * breaking and retries only to remote providers — wrapping a purely local one would
     * add a failure mode rather than protect against one.
     */
    default boolean isRemote() {
        return true;
    }
}
