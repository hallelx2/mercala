package com.mercala.catalog.events;

import java.util.UUID;

/**
 * Event published when a product has been added to the catalog.
 */
public record ProductAdded(UUID productId, UUID tenantId) {
}
