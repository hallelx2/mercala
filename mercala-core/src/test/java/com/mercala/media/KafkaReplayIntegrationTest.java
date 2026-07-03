package com.mercala.media;

import com.mercala.AbstractIntegrationTest;
import com.mercala.contracts.event.ImageResultEvent;
import com.mercala.platform.idempotency.ProcessedEventRepository;
import com.mercala.platform.multitenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    "mercala.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "image.results" })
@DirtiesContext
class KafkaReplayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private com.mercala.identity.TenantRepository tenantRepository;

    @Autowired
    private com.mercala.catalog.ProductRepository productRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void verifiesReplayRebuildsLocalState() {
        // 1. Create real Tenant and Product
        com.mercala.identity.Tenant tenant = tenantRepository.save(new com.mercala.identity.Tenant("replay-store", "Replay Store"));
        UUID tenantId = tenant.getId();

        com.mercala.catalog.Product product = productRepository.save(new com.mercala.catalog.Product(tenantId, "Shirt", "Premium cotton shirt", new java.math.BigDecimal("29.99")));
        UUID productId = product.getId();

        UUID eventId = UUID.randomUUID();
        String imageUrl = "http://localhost:9000/mercala-images/shirt.png";

        ImageResultEvent event = new ImageResultEvent(eventId, productId, tenantId, imageUrl);

        // 2. Publish to Kafka with proper headers using ProducerRecord
        org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "image.results",
                        productId.toString(),
                        event
                );
        record.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        kafkaTemplate.send(record);

        // 3. Await database write to confirm initial processing
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TenantContext.setCurrentTenant(tenantId);
            try {
                List<ProductImage> images = productImageRepository.findByProductId(productId);
                assertThat(images).hasSize(1);
                assertThat(images.get(0).getUrl()).isEqualTo(imageUrl);
            } finally {
                TenantContext.clear();
            }
        });

        // 4. Truncate/Delete local state in database (simulate state loss)
        productImageRepository.deleteAll();
        processedEventRepository.deleteAll();

        // Verify state is indeed lost/empty
        TenantContext.setCurrentTenant(tenantId);
        try {
            List<ProductImage> images = productImageRepository.findByProductId(productId);
            assertThat(images).isEmpty();
            assertThat(processedEventRepository.count()).isEqualTo(0);
        } finally {
            TenantContext.clear();
        }

        // 5. Trigger replay via admin REST endpoint
        ResponseEntity<Void> response = restTemplate.postForEntity("/api/media/replay", null, Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 6. Verify that state is rebuilt (consumer replayed from offset 0)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TenantContext.setCurrentTenant(tenantId);
            try {
                List<ProductImage> images = productImageRepository.findByProductId(productId);
                assertThat(images).hasSize(1);
                assertThat(images.get(0).getUrl()).isEqualTo(imageUrl);
            } finally {
                TenantContext.clear();
            }
        });
    }
}
