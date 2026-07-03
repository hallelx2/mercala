package com.mercala.media;

import com.mercala.contracts.event.ImageResultEvent;
import com.mercala.platform.multitenancy.TenantContext;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes image result events from Kafka and stores the image reference in the database.
 * Implements {@link ConsumerSeekAware} to support programmatically replaying messages from offset 0.
 */
@Component
@ConditionalOnProperty(name = "mercala.kafka.enabled", havingValue = "true")
public class ImageResultKafkaConsumer implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(ImageResultKafkaConsumer.class);

    private final ProductImageRepository productImageRepository;
    private final com.mercala.platform.idempotency.IdempotentConsumerService idempotentConsumerService;
    
    private ConsumerSeekCallback seekCallback;
    private java.util.Collection<TopicPartition> assignedPartitions = java.util.Collections.emptyList();
    private volatile boolean pendingReplay = false;

    public ImageResultKafkaConsumer(
            ProductImageRepository productImageRepository,
            com.mercala.platform.idempotency.IdempotentConsumerService idempotentConsumerService) {
        this.productImageRepository = productImageRepository;
        this.idempotentConsumerService = idempotentConsumerService;
    }

    @Override
    public void registerSeekCallback(ConsumerSeekCallback callback) {
        this.seekCallback = callback;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        this.seekCallback = callback;
        this.assignedPartitions = assignments.keySet();
    }

    @Override
    public void onIdleContainer(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        if (pendingReplay) {
            log.info("Executing deferred replay seek on consumer thread for partitions: {}", assignments.keySet());
            callback.seekToBeginning(assignments.keySet());
            pendingReplay = false;
        }
    }

    /**
     * Set the flag to trigger a seek to beginning on the next container idle event or poll iteration.
     */
    public void replayAll() {
        log.info("Scheduling deferred seek to beginning for image.results replay");
        this.pendingReplay = true;
    }

    @KafkaListener(
            topics = "${mercala.kafka.image-results-topic:image.results}",
            groupId = "${spring.kafka.consumer.group-id:mercala-core-group}"
    )
    @Transactional
    public void consume(ImageResultEvent event,
                        @Header("tenant_id") byte[] tenantIdBytes,
                        @Header(value = "correlation_id", required = false) byte[] correlationIdBytes) {
        String correlationId = correlationIdBytes != null ? new String(correlationIdBytes, StandardCharsets.UTF_8) : UUID.randomUUID().toString();
        org.slf4j.MDC.put("correlation_id", correlationId);
        try {
            log.info("Received image result event: eventId={}, productId={}, tenantId={}, imageUrl='{}'",
                    event.eventId(), event.productId(), event.tenantId(), event.imageUrl());

            if (pendingReplay && seekCallback != null && !assignedPartitions.isEmpty()) {
                log.info("Executing deferred replay seek on consumer thread during consume processing");
                seekCallback.seekToBeginning(assignedPartitions);
                pendingReplay = false;
                // Throw exception or return to force re-poll from the beginning
                throw new RuntimeException("Forcing offset reset for replay");
            }

            // Validate tenant_id header matches event tenantId
            String headerTenantId = new String(tenantIdBytes, StandardCharsets.UTF_8);
            if (!event.tenantId().toString().equals(headerTenantId)) {
                throw new IllegalArgumentException("Tenant ID mismatch: header=" + headerTenantId + ", event=" + event.tenantId());
            }

            if (idempotentConsumerService.checkAndRecord(event.eventId())) {
                log.info("Duplicate event ignored: eventId={}", event.eventId());
                return;
            }

            UUID previousTenant = TenantContext.getCurrentTenant();
            TenantContext.setCurrentTenant(event.tenantId());
            try {
                ProductImage productImage = new ProductImage(event.tenantId(), event.productId(), event.imageUrl());
                productImageRepository.save(productImage);
                log.info("Successfully attached generated image reference to product: productId={}", event.productId());
            } catch (Exception e) {
                log.error("Failed to attach image reference to product: productId={}", event.productId(), e);
                throw new RuntimeException("Error attaching generated image to product: " + event.productId(), e);
            } finally {
                if (previousTenant != null) {
                    TenantContext.setCurrentTenant(previousTenant);
                } else {
                    TenantContext.clear();
                }
            }
        } finally {
            org.slf4j.MDC.remove("correlation_id");
        }
    }
}
