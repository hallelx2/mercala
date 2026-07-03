package com.mercala.catalog.adapters;

import com.mercala.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.openai.api-url=http://localhost:54321",
        "app.openai.api-key=real-api-key-to-force-failures"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OpenAiEmbeddingClientResilienceTest extends AbstractIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private OpenAiEmbeddingClient openAiEmbeddingClient;

    @org.junit.jupiter.api.AfterEach
    void resetCircuitBreakers() {
        circuitBreakerRegistry.circuitBreaker("openai-embedding").reset();
    }

    @Test
    void verifiesCircuitBreakerTransitionsToOpenOnMultipleFailures() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("openai-embedding");
        assertThat(circuitBreaker).isNotNull();

        // Reset circuit breaker to closed state
        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Make 5 failing calls to hit the minimum number of calls threshold
        for (int i = 0; i < 5; i++) {
            float[] result = openAiEmbeddingClient.getEmbedding("test text");
            // Fallback should trigger and return mock embedding (not null or empty)
            assertThat(result).isNotNull().hasSize(1536);
        }

        // Verify the circuit is now open due to 100% failure rate
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // A 6th call should execute fallback immediately without attempting network
        float[] result = openAiEmbeddingClient.getEmbedding("another text");
        assertThat(result).isNotNull().hasSize(1536);
    }
}
