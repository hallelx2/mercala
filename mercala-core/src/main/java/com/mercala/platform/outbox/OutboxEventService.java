package com.mercala.platform.outbox;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for writing outbox events within the caller's transaction boundary.
 * <p>
 * This service is called inside the same {@code @Transactional} scope as the domain
 * state change, so the outbox row commits or rolls back atomically with the entity change.
 */
@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueues an event into the outbox table.
     * <p>
     * Must be called within an active transaction — the row is committed or rolled back
     * atomically with the enclosing state change.
     *
     * @param aggregateType logical aggregate name (e.g. "Product")
     * @param aggregateId   the aggregate's ID (usually productId)
     * @param tenantId      the tenant owning this aggregate
     * @param eventType     the event type (e.g. "ADDED", "UPDATED")
     * @param topic         the Kafka topic to publish to
     * @param payload       the event payload object (will be serialised to JSON)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, UUID aggregateId, UUID tenantId,
                        String eventType, String topic, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise outbox payload", e);
        }

        String correlationId = org.slf4j.MDC.get("correlation_id");
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                tenantId,
                eventType,
                topic,
                json,
                Instant.now(),
                correlationId
        );

        outboxEventRepository.save(event);
        log.debug("Enqueued outbox event: id={}, type={}, aggregate={}:{}",
                event.getId(), eventType, aggregateType, aggregateId);
    }
}
