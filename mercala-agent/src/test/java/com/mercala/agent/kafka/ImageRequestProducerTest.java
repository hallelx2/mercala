package com.mercala.agent.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.mercala.agent.chat.AgentContext;
import com.mercala.contracts.event.ImageRequestEvent;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.ai.openai.chat.enabled=false",
    "spring.ai.openai.embedding.enabled=false",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "image.requests" })
@DirtiesContext
class ImageRequestProducerTest {

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
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ImageRequestProducer producer;

    private Consumer<String, ImageRequestEvent> testConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = new HashMap<>(
                KafkaTestUtils.consumerProps("test-image-requests-group", "true", embeddedKafkaBroker));
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.springframework.kafka.support.serializer.JsonDeserializer");
        consumerProps.put("spring.json.trusted.packages", "com.mercala.contracts.event");

        ConsumerFactory<String, ImageRequestEvent> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
        testConsumer = consumerFactory.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(testConsumer, "image.requests");
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
        AgentContext.clear();
    }

    @Test
    void producesImageRequestEvent() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String prompt = "A clean product shot of a luxury shirt";

        AgentContext.set(new AgentContext(tenantId, UUID.randomUUID(), "MERCHANT_OWNER"));

        producer.sendImageRequest(productId, prompt);

        ConsumerRecords<String, ImageRequestEvent> records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(5));
        assertThat(records.count()).isEqualTo(1);

        ConsumerRecord<String, ImageRequestEvent> record = records.iterator().next();
        assertThat(record.key()).isEqualTo(productId.toString());
        
        ImageRequestEvent value = record.value();
        assertThat(value.productId()).isEqualTo(productId);
        assertThat(value.tenantId()).isEqualTo(tenantId);
        assertThat(value.prompt()).isEqualTo(prompt);
    }
}
