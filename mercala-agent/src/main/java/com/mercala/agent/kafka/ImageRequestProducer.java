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
    private static final String IMAGE_REQUESTS_TOPIC = "image.requests";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ImageRequestProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes an image request event to Kafka.
     */
    public void sendImageRequest(UUID productId, String prompt) {
        UUID tenantId = AgentContext.current().tenantId();
        log.info("Publishing image request: productId={}, tenantId={}, prompt='{}'",
                productId, tenantId, prompt);

        try {
            ImageRequestEvent event = new ImageRequestEvent(productId, tenantId, prompt);
            kafkaTemplate.send(IMAGE_REQUESTS_TOPIC, productId.toString(), event);
            log.info("Successfully published image request event to Kafka topic {}", IMAGE_REQUESTS_TOPIC);
        } catch (Exception e) {
            log.error("Failed to publish image request event to Kafka", e);
            throw new RuntimeException("Failed to request image generation via event-bus", e);
        }
    }
}
