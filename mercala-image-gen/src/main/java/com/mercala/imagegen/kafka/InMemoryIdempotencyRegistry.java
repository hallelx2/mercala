package com.mercala.imagegen.kafka;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory thread-safe registry to deduplicate consumed events in mercala-image-gen.
 */
@Component
public class InMemoryIdempotencyRegistry {

    private final Set<UUID> processedEvents = Collections.synchronizedSet(
        Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > 10000;
            }
        })
    );

    /**
     * Checks if the event has already been processed. If not, registers it.
     *
     * @param eventId the unique event identifier
     * @return true if the event was already processed (duplicate), false otherwise
     */
    public boolean isDuplicate(UUID eventId) {
        if (eventId == null) {
            return false;
        }
        synchronized (processedEvents) {
            if (processedEvents.contains(eventId)) {
                return true;
            }
            processedEvents.add(eventId);
            return false;
        }
    }
}
