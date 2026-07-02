package com.mercala.media;

import com.mercala.AbstractIntegrationTest;
import com.mercala.contracts.event.ImageResultEvent;
import com.mercala.platform.multitenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = {
    "mercala.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "image.results" })
@DirtiesContext
class ImageResultKafkaConsumerTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private com.mercala.identity.TenantRepository tenantRepository;

    @Autowired
    private com.mercala.catalog.ProductRepository productRepository;

    @Test
    void consumesImageResultAndAttachesToProduct() {
        // Create real Tenant and Product to satisfy foreign keys
        com.mercala.identity.Tenant tenant = tenantRepository.save(new com.mercala.identity.Tenant("media-store", "Media Store"));
        UUID tenantId = tenant.getId();

        com.mercala.catalog.Product product = productRepository.save(new com.mercala.catalog.Product(tenantId, "Wallet", "Premium Leather Wallet", new java.math.BigDecimal("49.99")));
        UUID productId = product.getId();

        String imageUrl = "http://localhost:9000/mercala-images/image.png";

        ImageResultEvent event = new ImageResultEvent(productId, tenantId, imageUrl);
        kafkaTemplate.send("image.results", productId.toString(), event);

        // Await database write under tenant context
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            TenantContext.setCurrentTenant(tenantId);
            try {
                List<ProductImage> images = productImageRepository.findByProductId(productId);
                assertFalse(images.isEmpty(), "Image should have been saved to the repository");
                assertEquals(imageUrl, images.get(0).getUrl());
                assertEquals(tenantId, images.get(0).getTenantId());
            } finally {
                TenantContext.clear();
            }
        });
    }
}
