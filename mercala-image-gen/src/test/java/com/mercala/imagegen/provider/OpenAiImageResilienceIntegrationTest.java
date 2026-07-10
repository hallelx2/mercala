package com.mercala.imagegen.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.image.ImageModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.ai.openai.image.enabled=true",
        "spring.ai.openai.api-key=dummy",
        "mercala.image-gen.provider=openai",
        "mercala.storage.endpoint=http://localhost:9000",
        "mercala.storage.access-key=dummy",
        "mercala.storage.secret-key=dummy",
        "mercala.storage.bucket=dummy"
})
public class OpenAiImageResilienceIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private ImageProvider imageProvider;

    @MockBean
    private ImageModel imageModel;

    @MockBean
    private com.mercala.imagegen.storage.StorageService storageService;

    @Test
    void verifiesCircuitBreakerTransitionsToOpenOnMultipleFailures() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("openai-image");
        assertThat(circuitBreaker).isNotNull();

        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Mock imageModel to always throw exception to trigger circuit breaker
        when(imageModel.call(any())).thenThrow(new RuntimeException("OpenAI image API failure"));

        // Make 5 failing calls to hit the minimum number of calls threshold
        for (int i = 0; i < 5; i++) {
            byte[] result = imageProvider.generateImage("test prompt");
            // Fallback should trigger and return mock image bytes (forest green PNG)
            assertThat(result).isNotNull().isNotEmpty();
        }

        // Verify the circuit is now open due to 100% failure rate
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // A 6th call should execute fallback immediately without attempting model call
        byte[] result = imageProvider.generateImage("another prompt");
        assertThat(result).isNotNull().isNotEmpty();
    }
}
