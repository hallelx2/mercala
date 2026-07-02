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
import com.mercala.contracts.event.ProductEvent;
import com.mercala.platform.multitenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);
    private static final String PRODUCT_EVENTS_TOPIC = "product.events";

    private final ProductRepository productRepository;
    private final EmbeddingPort embeddingPort;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${mercala.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public ProductEventListener(
            ProductRepository productRepository,
            EmbeddingPort embeddingPort,
            @Autowired(required = false) KafkaTemplate<String, Object> kafkaTemplate) {
        this.productRepository = productRepository;
        this.embeddingPort = embeddingPort;
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductAdded(ProductAdded event) {
        log.info("Handling ProductAdded event for product: {}", event.productId());
        publishToKafka(event.productId(), event.tenantId(), "ADDED");
        reembedProduct(event.productId(), event.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductUpdated(ProductUpdated event) {
        log.info("Handling ProductUpdated event for product: {}", event.productId());
        publishToKafka(event.productId(), event.tenantId(), "UPDATED");
        reembedProduct(event.productId(), event.tenantId());
    }

    private void publishToKafka(UUID productId, UUID tenantId, String eventType) {
        if (!kafkaEnabled) {
            log.debug("Kafka publishing is disabled, skipping message send");
            return;
        }
        if (kafkaTemplate != null) {
            try {
                ProductEvent productEvent = new ProductEvent(productId, tenantId, eventType);
                kafkaTemplate.send(PRODUCT_EVENTS_TOPIC, productId.toString(), productEvent);
                log.info("Successfully published {} event to Kafka topic {} for product {}", eventType, PRODUCT_EVENTS_TOPIC, productId);
            } catch (Exception e) {
                log.error("Failed to publish {} event to Kafka for product {}", eventType, productId, e);
            }
        } else {
            log.debug("KafkaTemplate not autowired, skipping publishing to {}", PRODUCT_EVENTS_TOPIC);
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
