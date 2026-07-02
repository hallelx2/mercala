package com.mercala.imagegen.kafka;

import com.mercala.contracts.event.ImageRequestEvent;
import com.mercala.contracts.event.ImageResultEvent;
import com.mercala.imagegen.provider.ImageProvider;
import com.mercala.imagegen.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.ai.openai.image.enabled=false",
    "spring.ai.openai.api-key=dummy",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "mercala.storage.endpoint=http://localhost:9000",
    "mercala.storage.access-key=dummy",
    "mercala.storage.secret-key=dummy",
    "mercala.storage.bucket=dummy"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "image.requests", "image.results" })
@DirtiesContext
class ImageRequestKafkaConsumerTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ImageRequestKafkaConsumer consumer;

    @MockBean
    private ImageProvider imageProvider;

    @MockBean
    private StorageService storageService;

    private final List<ImageResultEvent> receivedResultEvents = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(
            topics = "image.results",
            groupId = "mercala-image-gen-test-group"
    )
    public void listenResults(ImageResultEvent event) {
        receivedResultEvents.add(event);
    }

    @BeforeEach
    void setUp() {
        consumer.clearReceivedEvents();
        receivedResultEvents.clear();
    }

    @Test
    void consumesImageRequestAndProducesImageResult() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String prompt = "Create a premium leather wallet image";
        byte[] mockBytes = new byte[]{10, 20, 30};
        String mockUrl = "http://localhost:9000/mercala-images/" + tenantId + "/" + productId + ".png";

        // Configure Mocks
        when(imageProvider.generateImage(eq(prompt))).thenReturn(mockBytes);
        when(storageService.uploadImage(eq(tenantId), eq(productId), eq(mockBytes))).thenReturn(mockUrl);

        // Send request event
        ImageRequestEvent requestEvent = new ImageRequestEvent(productId, tenantId, prompt);
        kafkaTemplate.send("image.requests", productId.toString(), requestEvent);

        // 1. Verify consumer received the event
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ImageRequestEvent> receivedRequests = consumer.getReceivedEvents();
            assertFalse(receivedRequests.isEmpty(), "Consumer should have received the request event");
            assertEquals(productId, receivedRequests.get(0).productId());
        });

        // 2. Verify result event was produced and consumed by our test listener
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertFalse(receivedResultEvents.isEmpty(), "Result event should have been published to image.results topic");
            ImageResultEvent resultEvent = receivedResultEvents.get(0);
            assertEquals(productId, resultEvent.productId());
            assertEquals(tenantId, resultEvent.tenantId());
            assertEquals(mockUrl, resultEvent.imageUrl());
        });
    }

    @Test
    void consumesImageRequestAndHandlesFailuresGracefully() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String prompt = "Create a premium leather wallet image";

        // Mock generator failure
        when(imageProvider.generateImage(eq(prompt))).thenThrow(new RuntimeException("AI service unavailable"));

        // Send request event
        ImageRequestEvent requestEvent = new ImageRequestEvent(productId, tenantId, prompt);
        kafkaTemplate.send("image.requests", productId.toString(), requestEvent);

        // Verify consumer received the event
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ImageRequestEvent> receivedRequests = consumer.getReceivedEvents();
            assertFalse(receivedRequests.isEmpty(), "Consumer should have received the request event");
            assertEquals(productId, receivedRequests.get(0).productId());
        });

        // Sleep briefly to ensure no event is sent to results
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {}

        // Assert that result events remains empty
        assertEquals(0, receivedResultEvents.size(), "No result event should be published when image generation fails");
    }
}
