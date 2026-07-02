package com.mercala.platform.idempotency;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to handle deduplication logic for Kafka event consumers.
 */
@Service
public class IdempotentConsumerService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumerService.class);
    private final ProcessedEventRepository repository;

    public IdempotentConsumerService(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks if the event with the given ID has already been successfully processed.
     * If not, records it in the database.
     *
     * @param eventId the unique event identifier
     * @return true if the event has already been processed (duplicate), false otherwise
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean checkAndRecord(UUID eventId) {
        if (eventId == null) {
            log.debug("Event ID is null, skipping idempotency check");
            return false;
        }
        if (repository.existsById(eventId)) {
            log.warn("Duplicate event detected: eventId={}", eventId);
            return true;
        }
        repository.save(new ProcessedEvent(eventId));
        log.debug("Recorded event processing: eventId={}", eventId);
        return false;
    }
}
