package com.mercala.platform.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity representing a row in the {@code outbox_event} table.
 * <p>
 * Events are inserted within the same transaction as the domain state change.
 * The {@link OutboxRelay} polls for rows where {@code publishedAt} is null,
 * publishes them to Kafka, then stamps the {@code publishedAt} column.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, UUID tenantId,
                       String eventType, String topic, String payload, Instant createdAt, String correlationId) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
        this.correlationId = correlationId;
    }

    public UUID getId() { return id; }

    public String getAggregateType() { return aggregateType; }

    public UUID getAggregateId() { return aggregateId; }

    public UUID getTenantId() { return tenantId; }

    public String getEventType() { return eventType; }

    public String getTopic() { return topic; }

    public String getPayload() { return payload; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPublishedAt() { return publishedAt; }

    public String getCorrelationId() { return correlationId; }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
