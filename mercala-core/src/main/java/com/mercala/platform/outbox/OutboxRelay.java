package com.mercala.platform.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the {@code outbox_event} table for unpublished rows and relays them to Kafka.
 * <p>
 * Each poll iteration runs in its own transaction: it reads a batch of unpublished events,
 * publishes each one to Kafka (fire-and-forget — the message is durable once Kafka acks),
 * then stamps {@code published_at}. If the relay crashes between the Kafka send and the
 * DB update, the event remains unpublished and will be re-sent on the next poll —
 * yielding <b>at-least-once</b> delivery (idempotent consumers handle duplicates).
 * <p>
 * Enabled only when {@code mercala.kafka.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "mercala.kafka.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, Object> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Polls every 500 ms for unpublished outbox events and publishes them to Kafka.
     */
    @Scheduled(fixedDelayString = "${mercala.outbox.relay-interval-ms:500}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublished(BATCH_SIZE);
        if (unpublished.isEmpty()) {
            return;
        }

        log.info("Outbox relay: publishing {} event(s)", unpublished.size());

        for (OutboxEvent event : unpublished) {
            try {
                kafkaTemplate.send(
                        event.getTopic(),
                        event.getAggregateId().toString(),
                        event.getPayload()
                );

                event.markPublished();
                outboxEventRepository.save(event);

                log.debug("Published outbox event: id={}, topic={}, aggregate={}:{}",
                        event.getId(), event.getTopic(), event.getAggregateType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}, will retry on next poll",
                        event.getId(), e);
                // Don't mark published — it will be retried
            }
        }
    }
}
