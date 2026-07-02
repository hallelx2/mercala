package com.mercala.platform.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link OutboxEvent}.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns unpublished outbox events ordered by creation time (oldest first),
     * capped at {@code limit} to prevent flooding the relay.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL ORDER BY created_at ASC LIMIT :limit",
           nativeQuery = true)
    List<OutboxEvent> findUnpublished(@Param("limit") int limit);
}
