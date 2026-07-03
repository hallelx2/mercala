package com.mercala.agent.kafka;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.client.MercalaCoreClient;
import com.mercala.contracts.event.ProductEvent;

/**
 * Consumes product events from Kafka to asynchronously calculate product embeddings
 * using Spring AI and save them back to the core service.
 */
@Component
public class ProductEventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductEventKafkaConsumer.class);

    private final EmbeddingModel embeddingModel;
    private final MercalaCoreClient coreClient;
    private final InMemoryIdempotencyRegistry idempotencyRegistry;

    public ProductEventKafkaConsumer(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            MercalaCoreClient coreClient,
            InMemoryIdempotencyRegistry idempotencyRegistry) {
        this.embeddingModel = embeddingModel;
        this.coreClient = coreClient;
        this.idempotencyRegistry = idempotencyRegistry;
    }

    @KafkaListener(topics = "${mercala.kafka.product-events-topic:product.events}", groupId = "mercala-agent-group")
    public void consumeProductEvent(
            ProductEvent event,
            @org.springframework.messaging.handler.annotation.Header("tenant_id") byte[] tenantIdBytes,
            @org.springframework.messaging.handler.annotation.Header(value = "correlation_id", required = false) byte[] correlationIdBytes) {
        String correlationId = correlationIdBytes != null ? new String(correlationIdBytes, java.nio.charset.StandardCharsets.UTF_8) : UUID.randomUUID().toString();
        org.slf4j.MDC.put("correlation_id", correlationId);
        try {
            log.info("Received Kafka ProductEvent: eventId={}, productId={}, tenantId={}, type={}",
                    event.eventId(), event.productId(), event.tenantId(), event.eventType());

            // Validate tenant_id header matches event tenantId
            String headerTenantId = new String(tenantIdBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!event.tenantId().toString().equals(headerTenantId)) {
                throw new IllegalArgumentException("Tenant ID mismatch: header=" + headerTenantId + ", event=" + event.tenantId());
            }

            if (idempotencyRegistry.isDuplicate(event.eventId())) {
                log.info("Duplicate event ignored: eventId={}", event.eventId());
                return;
            }

            if ("DELETED".equalsIgnoreCase(event.eventType())) {
                log.info("Product deletion event received, skipping re-embedding");
                return;
            }

            if (embeddingModel == null) {
                log.warn("EmbeddingModel is not available, skipping product re-embedding");
                return;
            }

            // Set the request tenant context to ensure tenant isolation during HTTP calls
            AgentContext.set(new AgentContext(event.tenantId(), UUID.randomUUID(), "SHOPPER"));
            try {
                // 1. Fetch product details from core REST endpoint
                Map<String, Object> product = coreClient.getProduct(event.productId());
                if (product == null || product.containsKey("error")) {
                    log.warn("Could not retrieve product {} details from core", event.productId());
                    return;
                }

                // 2. Build the string to embed
                String name = (String) product.getOrDefault("name", "");
                String description = (String) product.getOrDefault("description", "");
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) product.getOrDefault("tags", List.of());

                StringBuilder sb = new StringBuilder(name);
                if (description != null && !description.isBlank()) {
                    sb.append(" ").append(description);
                }
                if (tags != null && !tags.isEmpty()) {
                    sb.append(" ").append(String.join(" ", tags));
                }
                String embedText = sb.toString().trim();

                if (embedText.isEmpty()) {
                    log.warn("Constructed empty text for product {} embedding, skipping", event.productId());
                    return;
                }

                log.info("Generating embedding for product: '{}' (length={})", name, embedText.length());

                // 3. Generate embedding vector using OpenAI / Mock embedding model via Spring AI
                float[] vector = embeddingModel.embed(embedText);

                // 4. Update the embedding back in core
                coreClient.updateProductEmbedding(event.productId(), vector);
                log.info("Successfully calculated and updated embedding for product {} via Kafka", event.productId());

            } catch (Exception e) {
                log.error("Failed to process ProductEvent for product {}", event.productId(), e);
            } finally {
                AgentContext.clear();
            }
        } finally {
            org.slf4j.MDC.remove("correlation_id");
        }
    }
}
