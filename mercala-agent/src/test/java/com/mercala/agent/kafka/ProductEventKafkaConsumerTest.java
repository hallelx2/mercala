package com.mercala.agent.kafka;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.mercala.agent.client.MercalaCoreClient;
import com.mercala.contracts.event.ProductEvent;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.ai.openai.chat.enabled=false",
    "spring.ai.openai.embedding.enabled=false",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "product.events" })
@DirtiesContext
class ProductEventKafkaConsumerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public ChatModel mockChatModel() {
            ChatModel mock = Mockito.mock(ChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                   .thenReturn(new ChatResponse(java.util.List.of()));
            return mock;
        }
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private MercalaCoreClient coreClient;

    @Test
    void consumesProductEventAndGeneratesEmbedding() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        // Mock getting product data from core Client
        Map<String, Object> productMock = Map.of(
                "id", productId.toString(),
                "tenantId", tenantId.toString(),
                "name", "Linen Shirt",
                "description", "Premium summer wear",
                "tags", List.of("shirt", "linen")
        );
        when(coreClient.getProduct(productId)).thenReturn(productMock);

        // Mock embedding generation
        float[] expectedVector = new float[]{0.1f, -0.2f, 0.5f};
        when(embeddingModel.embed("Linen Shirt Premium summer wear shirt linen")).thenReturn(expectedVector);

        // Send ProductEvent to Kafka
        ProductEvent event = new ProductEvent(productId, tenantId, "ADDED");
        org.apache.kafka.clients.producer.ProducerRecord<String, Object> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        "product.events",
                        productId.toString(),
                        event
                );
        record.headers().add("tenant_id", tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(record);

        // Verify consumer fetched product, generated embedding, and updated core
        verify(coreClient, timeout(5000)).getProduct(productId);
        verify(embeddingModel, timeout(5000)).embed("Linen Shirt Premium summer wear shirt linen");
        verify(coreClient, timeout(5000)).updateProductEmbedding(eq(productId), eq(expectedVector));
    }
}
