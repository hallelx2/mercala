package com.mercala.media;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    /**
     * Finds all images associated with a specific product.
     *
     * @param productId The ID of the product
     * @return List of product images
     */
    List<ProductImage> findByProductId(UUID productId);
}
