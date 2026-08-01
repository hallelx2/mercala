package com.mercala.imagegen.provider;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Selects an {@link ImageProvider} and degrades through a fallback chain when one fails.
 *
 * <p>This is the single place that knows about provider ordering. Individual providers
 * call one backend and throw on failure; the router decides what happens next. Adding a
 * provider means adding a class — no existing file grows a new branch.
 *
 * <p>Configure with:
 * <pre>
 *   mercala.image-gen.provider=replicate                  # primary
 *   mercala.image-gen.fallback-chain=pollinations,placeholder
 * </pre>
 *
 * <p>The chain is tried in order. Providers reporting {@link ImageProvider#isAvailable()
 * unavailable} are skipped without being counted as failures. Remote providers are
 * wrapped in a per-provider circuit breaker and retry, named {@code <provider>-image}.
 */
@Component
@Primary
public class ImageProviderRouter implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(ImageProviderRouter.class);

    private final Map<String, ImageProvider> providersByName;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final String primaryProvider;
    private final List<String> fallbackChain;

    public ImageProviderRouter(
            List<ImageProvider> providers,
            java.util.Optional<CircuitBreakerRegistry> circuitBreakerRegistry,
            java.util.Optional<RetryRegistry> retryRegistry,
            @Value("${mercala.image-gen.provider:replicate}") String primaryProvider,
            @Value("${mercala.image-gen.fallback-chain:pollinations,placeholder}") String fallbackChain) {
        this.providersByName = providers.stream()
                // Spring injects every ImageProvider bean, including this one. Routing to
                // itself would recurse forever.
                .filter(provider -> !(provider instanceof ImageProviderRouter))
                .collect(Collectors.toMap(
                        provider -> provider.name().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (first, second) -> first,
                        java.util.LinkedHashMap::new));
        this.circuitBreakerRegistry = circuitBreakerRegistry.orElse(null);
        this.retryRegistry = retryRegistry.orElse(null);
        this.primaryProvider = normalize(primaryProvider);
        this.fallbackChain = Arrays.stream(fallbackChain.split(","))
                .map(ImageProviderRouter::normalize)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    @PostConstruct
    void logConfiguration() {
        List<String> chain = resolveChain();
        log.info("Image provider chain: {} (registered: {})", chain, providersByName.keySet());

        if (!providersByName.containsKey(primaryProvider)) {
            log.warn("Configured image provider '{}' is not a registered provider — falling through to {}",
                    primaryProvider, fallbackChain);
        }

        // Surface typos at startup rather than as a silent skip on the first request that
        // needed the provider.
        List<String> unknown = chain.stream()
                .filter(name -> !providersByName.containsKey(name))
                .toList();
        if (!unknown.isEmpty()) {
            log.warn("Image provider chain references unknown providers {} — these will be skipped. Registered: {}",
                    unknown, providersByName.keySet());
        }
        if (chain.stream().noneMatch(name -> {
            ImageProvider provider = providersByName.get(name);
            return provider != null && !provider.isRemote();
        })) {
            log.warn("No local provider in the chain — a total outage of every remote provider will fail image generation");
        }
    }

    @Override
    public String name() {
        return "router";
    }

    @Override
    public byte[] generateImage(String prompt) {
        List<String> chain = resolveChain();
        RuntimeException lastFailure = null;

        for (String name : chain) {
            ImageProvider provider = providersByName.get(name);
            if (provider == null) {
                log.warn("Skipping unknown image provider '{}'", name);
                continue;
            }
            if (!provider.isAvailable()) {
                log.info("Skipping image provider '{}': not configured", name);
                continue;
            }

            try {
                byte[] bytes = invoke(provider, prompt);
                if (bytes == null || bytes.length == 0) {
                    throw new ImageGenerationException("Provider '" + name + "' returned no bytes");
                }
                log.info("Image generated by provider '{}' ({} bytes)", name, bytes.length);
                return bytes;
            } catch (CallNotPermittedException e) {
                log.warn("Circuit open for image provider '{}' — trying next in chain", name);
                lastFailure = e;
            } catch (RuntimeException e) {
                log.warn("Image provider '{}' failed: {} — trying next in chain", name, e.getMessage());
                lastFailure = e;
            }
        }

        throw new ImageGenerationException(
                "Every image provider in the chain " + chain + " failed or was unavailable", lastFailure);
    }

    /**
     * Primary first, then the configured fallbacks. Deduplicated so naming the primary
     * again in the fallback chain does not retry it twice.
     */
    private List<String> resolveChain() {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (!primaryProvider.isEmpty()) {
            chain.add(primaryProvider);
        }
        chain.addAll(fallbackChain);
        return new ArrayList<>(chain);
    }

    /**
     * Remote calls get a circuit breaker and retry keyed on the provider name, so one
     * failing backend cannot stall requests that a healthy backend could serve. Local
     * providers are called directly.
     */
    private byte[] invoke(ImageProvider provider, String prompt) {
        Supplier<byte[]> call = () -> provider.generateImage(prompt);

        if (!provider.isRemote()) {
            return call.get();
        }

        String instanceName = provider.name() + "-image";

        if (retryRegistry != null) {
            Retry retry = retryRegistry.retry(instanceName);
            call = Retry.decorateSupplier(retry, call);
        }
        if (circuitBreakerRegistry != null) {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
            call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        }

        return call.get();
    }

    /**
     * Provider names are configuration identifiers, not user-facing text, so the locale
     * is pinned. Default-locale lowercasing would map "OPENAI" to "openaı" under a
     * Turkish locale and the lookup would silently miss.
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
