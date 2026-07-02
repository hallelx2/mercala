package com.mercala.imagegen.kafka;

import com.mercala.contracts.event.ImageRequestEvent;
import org.junit.jupiter.api.BeforeEach;
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
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "image.requests" })
@DirtiesContext
class ImageRequestKafkaConsumerTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ImageRequestKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer.clearReceivedEvents();
    }

    @Test
    void consumesImageRequestEventCorrectly() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String prompt = "Create a premium leather wallet image";

        ImageRequestEvent event = new ImageRequestEvent(productId, tenantId, prompt);
        kafkaTemplate.send("image.requests", productId.toString(), event);

        // Await consumption using Awaitility for asynchronous test checking
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ImageRequestEvent> received = consumer.getReceivedEvents();
            assertFalse(received.isEmpty(), "Events should have been received by the consumer");
            ImageRequestEvent receivedEvent = received.get(0);
            assertEquals(productId, receivedEvent.productId());
            assertEquals(tenantId, receivedEvent.tenantId());
            assertEquals(prompt, receivedEvent.prompt());
        });
    }
}
