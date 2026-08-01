package com.mercala.imagegen.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Wires the real router and providers through Spring to verify that a failing remote
 * provider opens its own circuit and that the chain still returns an image.
 *
 * <p>The fallback chain is pinned to {@code placeholder} so the test makes no outbound
 * network calls — with the production default it would reach Pollinations.ai, making CI
 * dependent on a third party.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.image.enabled=true",
        "spring.ai.openai.api-key=dummy",
        "mercala.image-gen.provider=openai",
        "mercala.image-gen.fallback-chain=placeholder",
        "mercala.storage.endpoint=http://localhost:9000",
        "mercala.storage.access-key=dummy",
        "mercala.storage.secret-key=dummy",
        "mercala.storage.bucket=dummy",
        // One attempt per call, so the test does not sit through exponential backoff.
        // Overriding waitDuration instead would collide with the inherited interval
        // function and fail the context.
        "resilience4j.retry.instances.openai-image.maxAttempts=1",
})
class ImageProviderResilienceIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private ImageProvider imageProvider;

    @MockBean
    private ImageModel imageModel;

    @MockBean
    private com.mercala.imagegen.storage.StorageService storageService;

    @Test
    void routerIsInjectedAsThePrimaryImageProvider() {
        assertThat(imageProvider).isInstanceOf(ImageProviderRouter.class);
    }

    @Test
    void openaiCircuitOpensOnRepeatedFailuresWhileTheChainKeepsServingImages() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("openai-image");
        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        when(imageModel.call(any())).thenThrow(new RuntimeException("OpenAI image API failure"));

        // minimumNumberOfCalls is 5. Each router call records exactly one circuit-breaker
        // result regardless of how many times the retry fired underneath it.
        for (int i = 0; i < 5; i++) {
            assertThat(imageProvider.generateImage("test prompt"))
                    .as("the placeholder fallback must still produce an image")
                    .isNotNull()
                    .isNotEmpty();
        }

        assertThat(circuitBreaker.getState())
                .as("100% failure rate over the minimum call count should open the circuit")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // With the circuit open the router skips OpenAI outright and goes to placeholder.
        assertThat(imageProvider.generateImage("another prompt")).isNotNull().isNotEmpty();
    }
}
