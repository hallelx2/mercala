package com.mercala.imagegen.storage;

import java.util.UUID;

/**
 * Port interface for uploading generated image files to object storage.
 */
public interface StorageService {

    /**
     * Uploads the image bytes to storage.
     *
     * @param tenantId   The ID of the tenant owning the product
     * @param productId  The ID of the product
     * @param imageBytes The raw bytes of the generated image
     * @return The accessible URL of the stored image
     */
    String uploadImage(UUID tenantId, UUID productId, byte[] imageBytes);

    /**
     * Reads an object back out of storage, with credentials.
     *
     * <p>The merchant's uploaded original lives in the private bucket, so fetching it over
     * anonymous HTTP returns 403 anywhere the deployment is configured correctly. This is
     * the credentialed path, and the only one that works in production.
     *
     * @param url a URL this system previously produced
     * @throws IllegalArgumentException if the URL does not name an object in one of this
     *                                  deployment's buckets
     */
    byte[] readObject(String url);

    /**
     * Uploads under a named variant so it does not collide with the product's other images.
     *
     * <p>The plain three-argument form writes to a deterministic object name, which is
     * right for regeneration — the newest generated image replaces the last one. It is
     * wrong for an enhancement, where the merchant is comparing this render against their
     * original and possibly against two earlier attempts. Overwriting would delete the
     * thing they are choosing between.
     *
     * @param variant a short discriminator such as {@code enhanced}
     */
    String uploadImage(UUID tenantId, UUID productId, byte[] imageBytes, String variant);
}
