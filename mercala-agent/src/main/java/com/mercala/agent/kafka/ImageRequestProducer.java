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

        try {
            ImageRequestEvent event = new ImageRequestEvent(productId, tenantId, prompt);
            kafkaTemplate.send(imageRequestsTopic, productId.toString(), event);
            log.info("Successfully published image request event to Kafka topic {}", imageRequestsTopic);
        } catch (Exception e) {
            log.error("Failed to publish image request event to Kafka", e);
            throw new RuntimeException("Failed to request image generation via event-bus", e);
        }
    }
}
