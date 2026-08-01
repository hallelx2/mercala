package com.mercala.imagegen.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing and fallback behaviour. Resilience registries are absent here so the router
 * calls providers directly — circuit-breaker integration is covered by
 * {@link ImageProviderResilienceIntegrationTest}.
 */
class ImageProviderRouterTest {

    /** Records whether it was called, so tests can assert the chain stopped where expected. */
    private static class StubProvider implements ImageProvider {
        private final String name;
        private final boolean available;
        private final byte[] result;
        private final RuntimeException failure;
        boolean called = false;

        StubProvider(String name, boolean available, byte[] result, RuntimeException failure) {
            this.name = name;
            this.available = available;
            this.result = result;
            this.failure = failure;
        }

        static StubProvider succeeding(String name, String payload) {
            return new StubProvider(name, true, payload.getBytes(), null);
        }

        static StubProvider failing(String name) {
            return new StubProvider(name, true, null, new ImageGenerationException(name + " is down"));
        }

        static StubProvider unavailable(String name) {
            return new StubProvider(name, false, null, null);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public byte[] generateImage(String prompt) {
            called = true;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static ImageProviderRouter router(List<ImageProvider> providers, String primary, String chain) {
        return new ImageProviderRouter(providers, Optional.empty(), Optional.empty(), primary, chain);
    }

    @Test
    void usesTheConfiguredPrimaryProvider() {
        StubProvider replicate = StubProvider.succeeding("replicate", "from-replicate");
        StubProvider placeholder = StubProvider.succeeding("placeholder", "from-placeholder");

        byte[] result = router(List.of(replicate, placeholder), "replicate", "placeholder")
                .generateImage("a red shoe");

        assertThat(new String(result)).isEqualTo("from-replicate");
        assertThat(placeholder.called).as("fallback must not run when the primary succeeds").isFalse();
    }

    @Test
    void fallsThroughToTheNextProviderWhenThePrimaryFails() {
        StubProvider replicate = StubProvider.failing("replicate");
        StubProvider pollinations = StubProvider.succeeding("pollinations", "from-pollinations");

        byte[] result = router(List.of(replicate, pollinations), "replicate", "pollinations,placeholder")
                .generateImage("a red shoe");

        assertThat(replicate.called).isTrue();
        assertThat(new String(result)).isEqualTo("from-pollinations");
    }

    @Test
    void skipsUnavailableProvidersWithoutCallingThem() {
        StubProvider replicate = StubProvider.unavailable("replicate");
        StubProvider placeholder = StubProvider.succeeding("placeholder", "from-placeholder");

        byte[] result = router(List.of(replicate, placeholder), "replicate", "placeholder")
                .generateImage("a red shoe");

        assertThat(replicate.called).as("an unconfigured provider is skipped, not invoked").isFalse();
        assertThat(new String(result)).isEqualTo("from-placeholder");
    }

    @Test
    void walksTheWholeChainBeforeGivingUp() {
        StubProvider replicate = StubProvider.failing("replicate");
        StubProvider pollinations = StubProvider.failing("pollinations");
        StubProvider placeholder = StubProvider.succeeding("placeholder", "last-resort");

        byte[] result = router(List.of(replicate, pollinations, placeholder), "replicate", "pollinations,placeholder")
                .generateImage("a red shoe");

        assertThat(replicate.called).isTrue();
        assertThat(pollinations.called).isTrue();
        assertThat(new String(result)).isEqualTo("last-resort");
    }

    @Test
    void throwsWhenEveryProviderInTheChainFails() {
        StubProvider replicate = StubProvider.failing("replicate");
        StubProvider placeholder = StubProvider.failing("placeholder");

        assertThatThrownBy(() -> router(List.of(replicate, placeholder), "replicate", "placeholder")
                .generateImage("a red shoe"))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("failed or was unavailable");
    }

    @Test
    void treatsAnEmptyResultAsAFailureAndContinues() {
        StubProvider replicate = new StubProvider("replicate", true, new byte[0], null);
        StubProvider placeholder = StubProvider.succeeding("placeholder", "real-bytes");

        byte[] result = router(List.of(replicate, placeholder), "replicate", "placeholder")
                .generateImage("a red shoe");

        assertThat(new String(result)).isEqualTo("real-bytes");
    }

    @Test
    void doesNotRetryThePrimaryWhenItAlsoAppearsInTheFallbackChain() {
        var callLog = new ArrayList<String>();
        ImageProvider counting = new ImageProvider() {
            @Override
            public String name() {
                return "replicate";
            }

            @Override
            public boolean isRemote() {
                return false;
            }

            @Override
            public byte[] generateImage(String prompt) {
                callLog.add("call");
                throw new ImageGenerationException("down");
            }
        };
        StubProvider placeholder = StubProvider.succeeding("placeholder", "ok");

        router(List.of(counting, placeholder), "replicate", "replicate,placeholder")
                .generateImage("a red shoe");

        assertThat(callLog).as("the deduplicated chain calls each provider once").hasSize(1);
    }

    @Test
    void ignoresUnknownProviderNamesInTheChain() {
        StubProvider placeholder = StubProvider.succeeding("placeholder", "ok");

        byte[] result = router(List.of(placeholder), "does-not-exist", "placeholder")
                .generateImage("a red shoe");

        assertThat(new String(result)).isEqualTo("ok");
    }

    @Test
    void neverRoutesToItself() {
        StubProvider placeholder = StubProvider.succeeding("placeholder", "ok");
        List<ImageProvider> providers = new ArrayList<>(List.of(placeholder));

        ImageProviderRouter router = router(providers, "router", "placeholder");
        providers.add(router);

        // "router" resolves to nothing, so the chain proceeds to placeholder rather than
        // recursing into the router itself.
        assertThat(new String(router.generateImage("a red shoe"))).isEqualTo("ok");
    }
}
