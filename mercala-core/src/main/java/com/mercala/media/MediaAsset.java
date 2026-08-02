package com.mercala.media;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An image the merchant uploaded, before anything has been decided about it.
 *
 * <p>Separate from {@link ProductImage} because they answer different questions.
 * {@code ProductImage} says "this product is illustrated by this URL". A {@code MediaAsset}
 * says "this tenant put this file here" — which may become a product image, may be the
 * source an enhancement is made from, and may turn out to be neither.
 */
@Entity
@Table(name = "media_asset")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Set once the merchant says which product this is for; null until then. */
    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaAsset() {
    }

    public MediaAsset(UUID tenantId, UUID productId, String url, String contentType,
                      long sizeBytes, String originalName, UUID uploadedBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.url = url;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.originalName = originalName;
        this.uploadedBy = uploadedBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getUrl() {
        return url;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getOriginalName() {
        return originalName;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
