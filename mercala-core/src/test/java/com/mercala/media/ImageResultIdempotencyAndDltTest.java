package com.mercala.media;

import com.mercala.AbstractIntegrationTest;
import com.mercala.contracts.event.ImageResultEvent;
import com.mercala.platform.idempotency.ProcessedEventRepository;
import com.mercala.platform.multitenancy.TenantContext;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    "mercala.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "image.results", "image.results.DLT" })
@DirtiesContext
class ImageResultIdempotencyAndDltTest extends AbstractIntegrationTest {

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
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private Consumer<String, Object> dltConsumer;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("dlt-test-group", "true", embeddedKafkaBroker);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        
        dltConsumer = new DefaultKafkaConsumerFactory<String, Object>(consumerProps).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, "image.results.DLT");
    }

    @AfterEach
    void tearDown() {
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    @Test
    void verifiesDuplicateEventsAreDeduplicated() {
        com.mercala.identity.Tenant tenant = tenantRepository.save(new com.mercala.identity.Tenant("media-store-2", "Media Store 2"));
        UUID tenantId = tenant.getId();

        com.mercala.catalog.Product product = productRepository.save(new com.mercala.catalog.Product(tenantId, "Bag", "Canvas Bag", new java.math.BigDecimal("19.99")));
        UUID productId = product.getId();

        UUID eventId = UUID.randomUUID();
        String imageUrl = "http://localhost:9000/mercala-images/bag.png";

        ImageResultEvent event1 = new ImageResultEvent(eventId, productId, tenantId, imageUrl);
        ImageResultEvent event2 = new ImageResultEvent(eventId, productId, tenantId, "http://localhost:9000/mercala-images/bag-dup.png");

        // Send first event
        org.apache.kafka.clients.producer.ProducerRecord<String, Object> record1 =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "image.results",
                        productId.toString(),
                        event1
                );
        record1.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(record1);

        // Await database write
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            TenantContext.setCurrentTenant(tenantId);
            try {
                List<ProductImage> images = productImageRepository.findByProductId(productId);
                assertThat(images).hasSize(1);
                assertThat(images.get(0).getUrl()).isEqualTo(imageUrl);
            } finally {
                TenantContext.clear();
            }
        });

        // Send second event (duplicate eventId, but different url)
        org.apache.kafka.clients.producer.ProducerRecord<String, Object> record2 =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "image.results",
                        productId.toString(),
                        event2
                );
        record2.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(record2);

        // Wait another 2 seconds and verify that the duplicate was ignored (number of images is still 1)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        TenantContext.setCurrentTenant(tenantId);
        try {
            List<ProductImage> images = productImageRepository.findByProductId(productId);
            assertThat(images).hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void verifiesFailedEventsAreRoutedToDLT() {
        // Send image result event for a non-existent product ID
        UUID nonExistentProductId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        ImageResultEvent failingEvent = new ImageResultEvent(eventId, nonExistentProductId, tenantId, "http://localhost/poison.png");
        org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "image.results",
                        nonExistentProductId.toString(),
                        failingEvent
                );
        record.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(record);

        // Poll from DLT consumer to verify message is routed to DLT
        ConsumerRecord<String, Object> dltRecord = KafkaTestUtils.getSingleRecord(dltConsumer, "image.results.DLT", java.time.Duration.ofMillis(15000));
        assertThat(dltRecord).isNotNull();
        assertThat(dltRecord.key()).isEqualTo(nonExistentProductId.toString());
    }
}
