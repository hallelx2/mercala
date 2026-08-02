package com.mercala.imagegen.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source URL on an enhancement request originated in a chat message. Following it
 * unconditionally would make this worker — which runs on a host holding an IAM instance
 * profile — into an SSRF proxy. These tests are about what it refuses.
 */
class SourceImageLoaderTest {

    private static final String STORAGE = "http://localhost:9000";

    private SourceImageLoader loader(String endpoint) {
        return new SourceImageLoader(endpoint, "", 15_728_640L);
    }

    @Test
    void theCloudMetadataEndpointIsRefusedBeforeAnyConnectionIsOpened() {
        assertThatThrownBy(() -> loader(STORAGE)
                .load("http://169.254.169.254/latest/meta-data/iam/security-credentials/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mercala storage");
    }

    @Test
    void anArbitraryExternalHostIsRefused() {
        assertThatThrownBy(() -> loader(STORAGE).load("https://example.com/photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A host that merely contains the storage endpoint later in the string — the classic
     * {@code https://evil.com/?x=http://localhost:9000} shape — must not pass. The check is
     * a prefix match for exactly this reason.
     */
    @Test
    void aUrlThatOnlyMentionsStorageSomewhereInsideItIsRefused() {
        assertThatThrownBy(() -> loader(STORAGE).load("https://evil.example/?next=http://localhost:9000/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * With nothing configured there is no trusted host to compare against. Failing closed
     * costs a misconfigured deployment its enhancement feature; failing open would cost it
     * its credentials.
     */
    @Test
    void withNoStorageEndpointConfiguredNothingIsTrusted() {
        assertThatThrownBy(() -> loader("").load("http://localhost:9000/mercala-images/t/photo.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No storage endpoint");
    }

    @Test
    void aBlankUrlIsRejectedWithAnExplanation() {
        assertThatThrownBy(() -> loader(STORAGE).load("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source image URL");
    }

    /**
     * The allowed prefix is matched case-insensitively, because object URLs are assembled
     * from configuration whose scheme and host casing nobody controls carefully.
     */
    @Test
    void theAllowedPrefixIsNotCaseSensitive() {
        // Port 9 is the discard port and nothing listens on it, so getting as far as a
        // connection failure is the assertion: the host check passed rather than refusing.
        assertThatThrownBy(() -> loader("HTTP://LOCALHOST:9")
                .load("http://localhost:9/mercala-images/t/photo.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not fetch");
    }
}
