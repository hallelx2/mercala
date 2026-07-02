package com.mercala.catalog.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import com.mercala.contracts.event.ProductEvent;
import com.mercala.platform.multitenancy.TenantContext;
import com.mercala.platform.outbox.OutboxEventService;

@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductRepository productRepository;
    private final EmbeddingPort embeddingPort;
    private final OutboxEventService outboxEventService;
    private final String productEventsTopic;

    @Value("${mercala.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public ProductEventListener(
            ProductRepository productRepository,
            EmbeddingPort embeddingPort,
            @Autowired(required = false) OutboxEventService outboxEventService,
            @Value("${mercala.kafka.product-events-topic:product.events}") String productEventsTopic) {
        this.productRepository = productRepository;
        this.embeddingPort = embeddingPort;
        this.outboxEventService = outboxEventService;
        this.productEventsTopic = productEventsTopic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductAdded(ProductAdded event) {
        log.info("Handling ProductAdded event for product: {}", event.productId());
        enqueueOutboxEvent(event.productId(), event.tenantId(), "ADDED");
        reembedProduct(event.productId(), event.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductUpdated(ProductUpdated event) {
        log.info("Handling ProductUpdated event for product: {}", event.productId());
        enqueueOutboxEvent(event.productId(), event.tenantId(), "UPDATED");
        reembedProduct(event.productId(), event.tenantId());
    }

    /**
     * Enqueues the product event into the outbox table within the current transaction.
     * The OutboxRelay will pick it up and publish to Kafka asynchronously.
     */
    private void enqueueOutboxEvent(UUID productId, UUID tenantId, String eventType) {
        if (!kafkaEnabled) {
            log.debug("Kafka publishing is disabled, skipping outbox enqueue");
            return;
        }
        if (outboxEventService != null) {
            try {
                ProductEvent productEvent = new ProductEvent(productId, tenantId, eventType);
                outboxEventService.enqueue(
                        "Product",
                        productId,
                        tenantId,
                        eventType,
                        productEventsTopic,
                        productEvent
                );
                log.info("Enqueued {} outbox event for product {}", eventType, productId);
            } catch (Exception e) {
                log.error("Failed to enqueue {} outbox event for product {}", eventType, productId, e);
            }
        } else {
            log.debug("OutboxEventService not available, skipping outbox enqueue");
        }
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
