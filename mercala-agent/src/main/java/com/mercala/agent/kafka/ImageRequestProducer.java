package com.mercala.agent.kafka;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.mercala.agent.chat.AgentContext;
import com.mercala.contracts.event.ImageRequestEvent;

/**
 * Produces image generation requests to the 'image.requests' Kafka topic.
 */
@Component
public class ImageRequestProducer {

    private static final Logger log = LoggerFactory.getLogger(ImageRequestProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String imageRequestsTopic;

    public ImageRequestProducer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @org.springframework.beans.factory.annotation.Value("${mercala.kafka.image-requests-topic:image.requests}") String imageRequestsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.imageRequestsTopic = imageRequestsTopic;
    }

    /**
     * Publishes an image request event to Kafka.
     */
    public void sendImageRequest(UUID productId, String prompt) {
        UUID tenantId = AgentContext.current().tenantId();
        log.info("Publishing image request: productId={}, tenantId={}, prompt='{}'",
                productId, tenantId, prompt);
        publish(productId, tenantId, new ImageRequestEvent(productId, tenantId, prompt));
    }

    /**
     * Asks for the merchant's own photograph to be retouched rather than for a new image to
     * be invented. Same topic and same consumer — the mode on the event is what differs,
     * which keeps ordering per product intact between the two kinds of request.
     */
    public void sendEnhancementRequest(UUID productId, String sourceImageUrl, String instruction, Double strength) {
        UUID tenantId = AgentContext.current().tenantId();
        log.info("Publishing image enhancement request: productId={}, tenantId={}, source='{}', instruction='{}'",
                productId, tenantId, sourceImageUrl, instruction);
        publish(productId, tenantId,
                ImageRequestEvent.enhance(productId, tenantId, sourceImageUrl, instruction, strength));
    }

    private void publish(UUID productId, UUID tenantId, ImageRequestEvent event) {
        try {
            org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(
                            imageRequestsTopic,
                            productId.toString(),
                            event
                    );
            record.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String correlationId = org.slf4j.MDC.get("correlation_id");
            if (correlationId != null) {
                record.headers().add("correlation_id", correlationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            kafkaTemplate.send(record);
            log.info("Successfully published image request event to Kafka topic {}", imageRequestsTopic);
        } catch (Exception e) {
            log.error("Failed to publish image request event to Kafka", e);
            throw new RuntimeException("Failed to request image generation via event-bus", e);
        }
    }
}
