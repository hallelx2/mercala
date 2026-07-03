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
import java.util.UUID;

@Component
public class ImageRequestKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageRequestKafkaConsumer.class);

    private final ImageProvider imageProvider;
    private final StorageService storageService;
    private final ImageResultProducer imageResultProducer;
    private final InMemoryIdempotencyRegistry idempotencyRegistry;
    private final List<ImageRequestEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());

    public ImageRequestKafkaConsumer(
            ImageProvider imageProvider,
            StorageService storageService,
            ImageResultProducer imageResultProducer,
            InMemoryIdempotencyRegistry idempotencyRegistry) {
        this.imageProvider = imageProvider;
        this.storageService = storageService;
        this.imageResultProducer = imageResultProducer;
        this.idempotencyRegistry = idempotencyRegistry;
    }

    @KafkaListener(
            topics = "${mercala.kafka.image-requests-topic:image.requests}",
            groupId = "${spring.kafka.consumer.group-id:mercala-image-gen-group}"
    )
    public void consume(
            ImageRequestEvent event,
            @org.springframework.messaging.handler.annotation.Header("tenant_id") byte[] tenantIdBytes,
            @org.springframework.messaging.handler.annotation.Header(value = "correlation_id", required = false) byte[] correlationIdBytes) {
        String correlationId = correlationIdBytes != null ? new String(correlationIdBytes, java.nio.charset.StandardCharsets.UTF_8) : UUID.randomUUID().toString();
        org.slf4j.MDC.put("correlation_id", correlationId);
        try {
            log.info("Received image request event: eventId={}, productId={}, tenantId={}, prompt='{}'",
                    event.eventId(), event.productId(), event.tenantId(), event.prompt());

            // Validate tenant_id header matches event tenantId
            String headerTenantId = new String(tenantIdBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!event.tenantId().toString().equals(headerTenantId)) {
                throw new IllegalArgumentException("Tenant ID mismatch: header=" + headerTenantId + ", event=" + event.tenantId());
            }

            if (idempotencyRegistry.isDuplicate(event.eventId())) {
                log.info("Duplicate event ignored: eventId={}", event.eventId());
                return;
            }

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
                throw new RuntimeException("Error processing image request for product: " + event.productId(), e);
            }
        } finally {
            org.slf4j.MDC.remove("correlation_id");
        }
    }

    public List<ImageRequestEvent> getReceivedEvents() {
        return new ArrayList<>(receivedEvents);
    }

    public void clearReceivedEvents() {
        receivedEvents.clear();
    }
}
