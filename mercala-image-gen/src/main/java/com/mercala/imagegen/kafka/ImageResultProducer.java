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
        kafkaTemplate.send(topic, productId.toString(), event);
    }
}
