package com.mercala.platform.web;

import com.mercala.AbstractIntegrationTest;
import com.mercala.platform.outbox.OutboxEvent;
import com.mercala.platform.outbox.OutboxEventRepository;
import com.mercala.platform.outbox.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class CorrelationIdIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void verifiesHttpFilterPropagatesIncomingCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "custom-correlation-123");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange("/docs", HttpMethod.GET, entity, String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo("custom-correlation-123");
    }

    @Test
    void verifiesHttpFilterGeneratesCorrelationIdIfMissing() {
        HttpEntity<Void> entity = new HttpEntity<>(new HttpHeaders());

        ResponseEntity<String> response = restTemplate.exchange("/docs", HttpMethod.GET, entity, String.class);

        String correlationId = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(correlationId).isNotNull().isNotBlank();
        // Verify it's a valid UUID
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void verifiesOutboxEventCapturesMdcCorrelationId() {
        String testCorrelationId = "outbox-correlation-xyz";
        MDC.put("correlation_id", testCorrelationId);

        try {
            UUID aggregateId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();

            transactionTemplate.executeWithoutResult(status -> {
                outboxEventService.enqueue(
                        "TestAggregate",
                        aggregateId,
                        tenantId,
                        "CREATED",
                        "test.topic",
                        "{\"value\": \"test\"}"
                );
            });

            List<OutboxEvent> events = outboxEventRepository.findUnpublished(10);
            OutboxEvent matchedEvent = events.stream()
                    .filter(e -> e.getAggregateId().equals(aggregateId))
                    .findFirst()
                    .orElse(null);

            assertThat(matchedEvent).isNotNull();
            assertThat(matchedEvent.getCorrelationId()).isEqualTo(testCorrelationId);

        } finally {
            MDC.remove("correlation_id");
        }
    }
}
