package com.mercala.catalog.events;

import java.util.UUID;

/**
 * Event published when a product has been updated in the catalog.
 */
public record ProductUpdated(UUID productId, UUID tenantId) {
}
