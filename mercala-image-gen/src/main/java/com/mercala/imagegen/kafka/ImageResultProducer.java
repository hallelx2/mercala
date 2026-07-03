package com.mercala.imagegen.kafka;

import com.mercala.contracts.event.ImageResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ImageResultProducer {

    private static final Logger log = LoggerFactory.getLogger(ImageResultProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public ImageResultProducer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${mercala.kafka.image-results-topic:image.results}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Publishes an ImageResultEvent to the image.results topic.
     *
     * @param productId The ID of the target product
     * @param tenantId  The ID of the tenant owning the product
     * @param imageUrl  The public URL of the uploaded image
     */
    public void publishImageResult(UUID productId, UUID tenantId, String imageUrl) {
        ImageResultEvent event = new ImageResultEvent(productId, tenantId, imageUrl);
        log.info("Publishing image result event: topic={}, productId={}, tenantId={}, imageUrl='{}'",
                topic, productId, tenantId, imageUrl);
        
        org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        topic,
                        productId.toString(),
                        event
                );
        record.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String correlationId = org.slf4j.MDC.get("correlation_id");
        if (correlationId != null) {
            record.headers().add("correlation_id", correlationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish image result event to topic={} for productId={}", topic, productId, ex);
                    } else {
                        log.debug("Successfully published image result event to topic={} for productId={}", topic, productId);
                    }
                });
    }
}
