package com.mercala.imagegen.kafka;

import com.mercala.contracts.event.ImageRequestEvent;
import com.mercala.imagegen.provider.ImageProvider;
import com.mercala.imagegen.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ImageRequestKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageRequestKafkaConsumer.class);

    private final ImageProvider imageProvider;
    private final StorageService storageService;
    private final ImageResultProducer imageResultProducer;
    private final List<ImageRequestEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());

    public ImageRequestKafkaConsumer(
            ImageProvider imageProvider,
            StorageService storageService,
            ImageResultProducer imageResultProducer) {
        this.imageProvider = imageProvider;
        this.storageService = storageService;
        this.imageResultProducer = imageResultProducer;
    }

    @KafkaListener(
            topics = "${mercala.kafka.image-requests-topic:image.requests}",
            groupId = "${spring.kafka.consumer.group-id:mercala-image-gen-group}"
    )
    public void consume(ImageRequestEvent event) {
        log.info("Received image request event: productId={}, tenantId={}, prompt='{}'",
                event.productId(), event.tenantId(), event.prompt());
        receivedEvents.add(event);

        try {
            // 1. Generate image bytes
            byte[] imageBytes = imageProvider.generateImage(event.prompt());
            if (imageBytes == null || imageBytes.length == 0) {
                throw new RuntimeException("Generated image bytes are empty/null");
            }

            // 2. Upload to storage
            String imageUrl = storageService.uploadImage(event.tenantId(), event.productId(), imageBytes);

            // 3. Publish result event
            imageResultProducer.publishImageResult(event.productId(), event.tenantId(), imageUrl);
        } catch (Exception e) {
            log.error("Failed to complete image generation lifecycle for productId={}", event.productId(), e);
        }
    }

    public List<ImageRequestEvent> getReceivedEvents() {
        return new ArrayList<>(receivedEvents);
    }

    public void clearReceivedEvents() {
        receivedEvents.clear();
    }
}
