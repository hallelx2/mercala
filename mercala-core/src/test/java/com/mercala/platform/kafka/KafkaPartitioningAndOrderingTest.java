package com.mercala.platform.kafka;

import com.mercala.AbstractIntegrationTest;
import com.mercala.contracts.event.ImageResultEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
    "mercala.kafka.enabled=true",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "mercala.kafka.product-events-topic=test.product.events",
    "mercala.kafka.image-results-topic=test.image.results"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, topics = { "test.product.events", "test.image.results" })
@DirtiesContext
class KafkaPartitioningAndOrderingTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private NewTopic productEventsTopic;

    @Autowired
    private NewTopic imageResultsTopic;

    @Test
    void verifiesTopicConfigurations() {
        assertThat(productEventsTopic.name()).isEqualTo("test.product.events");
        assertThat(productEventsTopic.numPartitions()).isEqualTo(3);

        assertThat(imageResultsTopic.name()).isEqualTo("test.image.results");
        assertThat(imageResultsTopic.numPartitions()).isEqualTo(3);
    }

    @Test
    void verifiesSameKeysAreRoutedToSamePartition() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String imageUrl1 = "http://localhost:9000/mercala-images/image1.png";
        String imageUrl2 = "http://localhost:9000/mercala-images/image2.png";

        ImageResultEvent event1 = new ImageResultEvent(productId, tenantId, imageUrl1);
        ImageResultEvent event2 = new ImageResultEvent(productId, tenantId, imageUrl2);

        CompletableFuture<SendResult<String, Object>> future1 = kafkaTemplate.send(
                "test.image.results",
                productId.toString(),
                event1
        );
        CompletableFuture<SendResult<String, Object>> future2 = kafkaTemplate.send(
                "test.image.results",
                productId.toString(),
                event2
        );

        SendResult<String, Object> result1 = future1.get(5, TimeUnit.SECONDS);
        SendResult<String, Object> result2 = future2.get(5, TimeUnit.SECONDS);

        int partition1 = result1.getRecordMetadata().partition();
        int partition2 = result2.getRecordMetadata().partition();

        // Verify they land on the exact same partition ensuring strict ordering
        assertEquals(partition1, partition2, "Messages sharing the same key must route to the same partition");
    }

    @Test
    void verifiesDifferentKeysAreDistributedAcrossPartitions() throws Exception {
        UUID tenantId = UUID.randomUUID();
        int[] partitions = new int[15];

        for (int i = 0; i < 15; i++) {
            UUID productId = UUID.randomUUID();
            ImageResultEvent event = new ImageResultEvent(productId, tenantId, "url-" + i);
            SendResult<String, Object> result = kafkaTemplate.send(
                    "test.image.results",
                    productId.toString(),
                    event
            ).get(5, TimeUnit.SECONDS);
            partitions[i] = result.getRecordMetadata().partition();
        }

        // Verify that not all messages went to partition 0 (at least some distribution exists across partitions 0, 1, 2)
        boolean hasNonZero = false;
        for (int p : partitions) {
            if (p != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }
}
