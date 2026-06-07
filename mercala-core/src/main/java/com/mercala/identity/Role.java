package com.mercala.identity;

/**
 * Authorization roles carried in the JWT and enforced via {@code @PreAuthorize} (HAL-127).
 */
public enum Role {
    /** Platform operator across all tenants. */
    PLATFORM_ADMIN,
    /** Owner of a merchant store (tenant). */
    MERCHANT_OWNER,
    /** Staff member of a merchant store. */
    MERCHANT_STAFF,
    /** End customer shopping a store. */
    SHOPPER
}
