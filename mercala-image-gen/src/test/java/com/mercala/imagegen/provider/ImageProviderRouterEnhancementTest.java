package com.mercala.imagegen.provider;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enhancement walks the same chain as generation but with a capability filter in front of
 * it. The distinction the tests here defend is between "this provider does not offer
 * image-to-image" and "this provider is failing" — treating the first as the second would
 * open a circuit breaker and take the provider's generation down with it.
 */
class ImageProviderRouterEnhancementTest {

    private static class StubProvider implements ImageProvider {
        private final String name;
        private final boolean available;
        private final boolean enhances;
        private final byte[] result;
        private final RuntimeException failure;
        boolean generateCalled;
        boolean enhanceCalled;
        String lastInstruction;
        double lastStrength;

        StubProvider(String name, boolean available, boolean enhances, byte[] result, RuntimeException failure) {
            this.name = name;
            this.available = available;
            this.enhances = enhances;
            this.result = result;
            this.failure = failure;
        }

        static StubProvider enhancing(String name, String payload) {
            return new StubProvider(name, true, true, payload.getBytes(), null);
        }

        static StubProvider generateOnly(String name) {
            return new StubProvider(name, true, false, "generated".getBytes(), null);
        }

        static StubProvider failingEnhancer(String name) {
            return new StubProvider(name, true, true, null, new ImageGenerationException(name + " is down"));
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
        public boolean supportsEnhancement() {
            return enhances;
        }

        @Override
        public byte[] generateImage(String prompt) {
            generateCalled = true;
            return result;
        }

        @Override
        public byte[] enhanceImage(byte[] sourceImage, String instruction, double strength) {
            enhanceCalled = true;
            lastInstruction = instruction;
            lastStrength = strength;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static ImageProviderRouter router(List<ImageProvider> providers, String primary, String chain) {
        return new ImageProviderRouter(providers, Optional.empty(), Optional.empty(), primary, chain);
    }

    private static final byte[] SOURCE = "the merchant's photo".getBytes();

    @Test
    void aProviderThatCannotEnhanceIsSteppedOverRatherThanCalled() {
        StubProvider cannot = StubProvider.generateOnly("cannot");
        StubProvider can = StubProvider.enhancing("can", "retouched");

        byte[] result = router(List.of(cannot, can), "cannot", "can")
                .enhanceImage(SOURCE, "clean the background", 0.3);

        assertThat(new String(result)).isEqualTo("retouched");
        assertThat(cannot.enhanceCalled).as("an incapable provider must not be called at all").isFalse();
        assertThat(can.enhanceCalled).isTrue();
    }

    @Test
    void theInstructionAndStrengthReachTheProviderUnchanged() {
        StubProvider can = StubProvider.enhancing("can", "retouched");

        router(List.of(can), "can", "").enhanceImage(SOURCE, "studio lighting", 0.55);

        assertThat(can.lastInstruction).isEqualTo("studio lighting");
        assertThat(can.lastStrength).isEqualTo(0.55);
    }

    @Test
    void aFailingEnhancerFallsThroughToTheNextOneThatCan() {
        StubProvider broken = StubProvider.failingEnhancer("broken");
        StubProvider working = StubProvider.enhancing("working", "retouched");

        byte[] result = router(List.of(broken, working), "broken", "working")
                .enhanceImage(SOURCE, "clean the background", 0.3);

        assertThat(new String(result)).isEqualTo("retouched");
        assertThat(broken.enhanceCalled).isTrue();
        assertThat(working.enhanceCalled).isTrue();
    }

    /**
     * The consumer asks this before accepting a job, so that a merchant with no configured
     * image-to-image provider is told immediately rather than after the upload and the wait.
     */
    @Test
    void theRouterReportsWhetherAnythingInTheChainCanEnhance() {
        assertThat(router(List.of(StubProvider.generateOnly("a")), "a", "").supportsEnhancement()).isFalse();
        assertThat(router(List.of(StubProvider.enhancing("a", "x")), "a", "").supportsEnhancement()).isTrue();
    }

    /** A provider outside the configured chain must not be reached into. */
    @Test
    void aCapableProviderThatIsNotInTheChainIsNotUsed() {
        StubProvider outsider = StubProvider.enhancing("outsider", "retouched");
        StubProvider inChain = StubProvider.generateOnly("inchain");

        ImageProviderRouter router = router(List.of(outsider, inChain), "inchain", "");

        assertThat(router.supportsEnhancement()).isFalse();
        assertThatThrownBy(() -> router.enhanceImage(SOURCE, "clean it up", 0.3))
                .isInstanceOf(ImageGenerationException.class);
        assertThat(outsider.enhanceCalled).isFalse();
    }

    @Test
    void aChainWithNoEnhancerFailsWithAMessageThatNamesTheOperation() {
        assertThatThrownBy(() -> router(List.of(StubProvider.generateOnly("a")), "a", "")
                .enhanceImage(SOURCE, "clean it up", 0.3))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("enhance");
    }

    /** Adding enhancement must not have changed how generation routes. */
    @Test
    void generationStillIgnoresTheEnhancementCapabilityEntirely() {
        StubProvider generateOnly = StubProvider.generateOnly("a");

        byte[] result = router(List.of(generateOnly), "a", "").generateImage("a navy linen shirt");

        assertThat(new String(result)).isEqualTo("generated");
        assertThat(generateOnly.generateCalled).isTrue();
    }
}
