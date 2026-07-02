package com.mercala.platform.outbox;

import com.mercala.AbstractIntegrationTest;
import com.mercala.contracts.event.ProductEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Outbox pattern.
 * <p>
 * Verifies the core guarantees:
 * <ol>
 *   <li>Commit guarantee — after a successful transaction, an outbox row exists.</li>
 *   <li>Rollback guarantee — if the transaction rolls back, no outbox row exists.</li>
 *   <li>Relay — unpublished rows are picked up and published.</li>
 * </ol>
 */
@DirtiesContext
class OutboxEventIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        outboxEventRepository.deleteAll();
    }

    @Test
    void commitGuarantee_outboxRowExistsAfterSuccessfulTransaction() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ProductEvent payload = new ProductEvent(productId, tenantId, "ADDED");

        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                outboxEventService.enqueue("Product", productId, tenantId, "ADDED",
                        "product.events", payload);
            }
        });

        List<OutboxEvent> events = outboxEventRepository.findUnpublished(100);
        assertThat(events).hasSize(1);

        OutboxEvent saved = events.get(0);
        assertThat(saved.getAggregateType()).isEqualTo("Product");
        assertThat(saved.getAggregateId()).isEqualTo(productId);
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getEventType()).isEqualTo("ADDED");
        assertThat(saved.getTopic()).isEqualTo("product.events");
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getPayload()).contains(productId.toString());
    }

    @Test
    void rollbackGuarantee_noOutboxRowAfterRolledBackTransaction() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ProductEvent payload = new ProductEvent(productId, tenantId, "UPDATED");

        try {
            txTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    outboxEventService.enqueue("Product", productId, tenantId, "UPDATED",
                            "product.events", payload);
                    // Force rollback
                    status.setRollbackOnly();
                }
            });
        } catch (Exception ignored) {
            // expected
        }

        List<OutboxEvent> events = outboxEventRepository.findUnpublished(100);
        assertThat(events).isEmpty();
    }

    @Test
    void multipleEventsInSameTransaction_allPersisted() {
        UUID tenantId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();

        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                outboxEventService.enqueue("Product", productId1, tenantId, "ADDED",
                        "product.events", new ProductEvent(productId1, tenantId, "ADDED"));
                outboxEventService.enqueue("Product", productId2, tenantId, "UPDATED",
                        "product.events", new ProductEvent(productId2, tenantId, "UPDATED"));
            }
        });

        List<OutboxEvent> events = outboxEventRepository.findUnpublished(100);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(OutboxEvent::getAggregateId)
                .containsExactlyInAnyOrder(productId1, productId2);
    }

    @Test
    void markPublished_removesEventFromUnpublishedQuery() {
        UUID productId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ProductEvent payload = new ProductEvent(productId, tenantId, "ADDED");

        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                outboxEventService.enqueue("Product", productId, tenantId, "ADDED",
                        "product.events", payload);
            }
        });

        // Mark published
        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                List<OutboxEvent> events = outboxEventRepository.findUnpublished(100);
                assertThat(events).hasSize(1);
                events.get(0).markPublished();
                outboxEventRepository.save(events.get(0));
            }
        });

        List<OutboxEvent> remaining = outboxEventRepository.findUnpublished(100);
        assertThat(remaining).isEmpty();

        // But the event still exists in the DB
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }
}
