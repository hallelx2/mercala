package com.mercala.platform.idempotency;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing a successfully processed event ID.
 * Used to guarantee idempotent message consumption.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
        this.createdAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
