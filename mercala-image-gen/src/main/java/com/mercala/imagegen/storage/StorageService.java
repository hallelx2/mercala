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
}
