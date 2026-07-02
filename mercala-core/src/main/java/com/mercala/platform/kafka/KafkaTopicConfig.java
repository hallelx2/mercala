package com.mercala.platform.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "mercala.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic productEventsTopic(
            @Value("${mercala.kafka.product-events-topic:product.events}") String topicName) {
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic imageResultsTopic(
            @Value("${mercala.kafka.image-results-topic:image.results}") String topicName) {
        return TopicBuilder.name(topicName)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic productEventsDltTopic(
            @Value("${mercala.kafka.product-events-topic:product.events}") String topicName) {
        return TopicBuilder.name(topicName + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic imageResultsDltTopic(
            @Value("${mercala.kafka.image-results-topic:image.results}") String topicName) {
        return TopicBuilder.name(topicName + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
