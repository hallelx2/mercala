package com.mercala.imagegen.kafka;

import com.mercala.contracts.event.ImageRequestEvent;
import com.mercala.imagegen.provider.ImageProvider;
import com.mercala.imagegen.storage.SourceImageLoader;
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
    private final SourceImageLoader sourceImageLoader;
    private final List<ImageRequestEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());

    public ImageRequestKafkaConsumer(
            ImageProvider imageProvider,
            StorageService storageService,
            ImageResultProducer imageResultProducer,
            InMemoryIdempotencyRegistry idempotencyRegistry,
            SourceImageLoader sourceImageLoader) {
        this.imageProvider = imageProvider;
        this.storageService = storageService;
        this.imageResultProducer = imageResultProducer;
        this.idempotencyRegistry = idempotencyRegistry;
        this.sourceImageLoader = sourceImageLoader;
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
                if (event.isEnhancement()) {
                    enhance(event);
                } else {
                    generate(event);
                }
            } catch (Exception e) {
                log.error("Failed to complete image {} lifecycle for productId={}",
                        event.mode(), event.productId(), e);
                throw new RuntimeException("Error processing image request for product: " + event.productId(), e);
            }
        } finally {
            org.slf4j.MDC.remove("correlation_id");
        }
    }

    /** Text to image: the product has no photograph, so one is invented from the prompt. */
    private void generate(ImageRequestEvent event) {
        byte[] imageBytes = imageProvider.generateImage(event.prompt());
        if (imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("Generated image bytes are empty/null");
        }

        String imageUrl = storageService.uploadImage(event.tenantId(), event.productId(), imageBytes);
        imageResultProducer.publishImageResult(event.productId(), event.tenantId(), imageUrl);
    }

    /**
     * Image to image over the merchant's own photograph.
     *
     * <p>The capability check comes first and fails fast. Without it the merchant uploads a
     * photo, waits through the provider chain, and is told at the end that nothing in the
     * chain could ever have done it — a slow way to deliver a configuration error.
     */
    private void enhance(ImageRequestEvent event) {
        if (!imageProvider.supportsEnhancement()) {
            throw new IllegalStateException(
                    "No configured image provider supports enhancement — set a provider with an "
                            + "image-to-image model before offering it to merchants");
        }

        byte[] source = sourceImageLoader.load(event.sourceImageUrl());
        byte[] enhanced = imageProvider.enhanceImage(source, event.instruction(), event.strength());
        if (enhanced == null || enhanced.length == 0) {
            throw new RuntimeException("Enhanced image bytes are empty/null");
        }

        // A variant name, so this render lands beside the merchant's original and any
        // earlier attempt rather than on top of them.
        String imageUrl = storageService.uploadImage(
                event.tenantId(), event.productId(), enhanced, "enhanced");
        imageResultProducer.publishEnhancementResult(
                event.productId(), event.tenantId(), imageUrl, event.sourceImageUrl());
    }

    public List<ImageRequestEvent> getReceivedEvents() {
        return new ArrayList<>(receivedEvents);
    }

    public void clearReceivedEvents() {
        receivedEvents.clear();
    }
}
