package com.mercala.media;

import com.mercala.contracts.event.ImageResultEvent;
import com.mercala.platform.multitenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "mercala.kafka.enabled", havingValue = "true")
public class ImageResultKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageResultKafkaConsumer.class);

    private final ProductImageRepository productImageRepository;
    private final com.mercala.platform.idempotency.IdempotentConsumerService idempotentConsumerService;

    public ImageResultKafkaConsumer(
            ProductImageRepository productImageRepository,
            com.mercala.platform.idempotency.IdempotentConsumerService idempotentConsumerService) {
        this.productImageRepository = productImageRepository;
        this.idempotentConsumerService = idempotentConsumerService;
    }

    @KafkaListener(
            topics = "${mercala.kafka.image-results-topic:image.results}",
            groupId = "${spring.kafka.consumer.group-id:mercala-core-group}"
    )
    @Transactional
    public void consume(ImageResultEvent event) {
        log.info("Received image result event: eventId={}, productId={}, tenantId={}, imageUrl='{}'",
                event.eventId(), event.productId(), event.tenantId(), event.imageUrl());

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
    }
}
