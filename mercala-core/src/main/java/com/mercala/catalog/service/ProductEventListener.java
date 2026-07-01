package com.mercala.catalog.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mercala.catalog.Product;
import com.mercala.catalog.ProductRepository;
import com.mercala.catalog.events.ProductAdded;
import com.mercala.catalog.events.ProductUpdated;
import com.mercala.catalog.ports.EmbeddingPort;
import com.mercala.platform.multitenancy.TenantContext;

@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductRepository productRepository;
    private final EmbeddingPort embeddingPort;

    public ProductEventListener(ProductRepository productRepository, EmbeddingPort embeddingPort) {
        this.productRepository = productRepository;
        this.embeddingPort = embeddingPort;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductAdded(ProductAdded event) {
        log.info("Handling ProductAdded event for product: {}", event.productId());
        reembedProduct(event.productId(), event.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductUpdated(ProductUpdated event) {
        log.info("Handling ProductUpdated event for product: {}", event.productId());
        reembedProduct(event.productId(), event.tenantId());
    }

    private void reembedProduct(UUID productId, UUID tenantId) {
        UUID previousTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            Product product = productRepository.findByTenantIdAndId(tenantId, productId).orElse(null);
            if (product == null) {
                log.warn("Product not found for embedding: {}", productId);
                return;
            }

            String embedText = product.getName() + " " + (product.getDescription() != null ? product.getDescription() : "");
            if (product.getTags() != null && !product.getTags().isEmpty()) {
                embedText += " " + String.join(" ", product.getTags());
            }

            float[] embedding = embeddingPort.getEmbedding(embedText);
            product.setEmbedding(embedding);
            productRepository.save(product);
            log.info("Successfully re-embedded product: {}", productId);
        } catch (Exception e) {
            log.error("Failed to generate embedding for product: {}", productId, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.setCurrentTenant(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
