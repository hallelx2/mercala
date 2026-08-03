package com.mercala.imagegen.storage;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loader now delegates the interesting decision — is this URL one of ours — to
 * {@link ObjectRef}, and reads with credentials rather than over anonymous HTTP. What is
 * left here is the bound on how much it will hand a provider, and the two shapes of empty.
 *
 * <p>The old version of this class tested an HTTP allow-list. That check has not been
 * weakened; it moved, and it is stricter, because the URL now has to name a bucket as well
 * as a host. {@link ObjectRefTest} is where it lives.
 */
class SourceImageLoaderTest {

    private static final String URL = "http://localhost:9000/mercala-images/t/uploads/photo.jpg";

    /** Stands in for the object store: hands back what it was told to, and records the ask. */
    private static class StubStorage implements StorageService {
        private final byte[] payload;
        private final RuntimeException failure;
        String lastRequestedUrl;

        StubStorage(byte[] payload, RuntimeException failure) {
            this.payload = payload;
            this.failure = failure;
        }

        @Override
        public String uploadImage(UUID tenantId, UUID productId, byte[] imageBytes) {
            return uploadImage(tenantId, productId, imageBytes, null);
        }

        @Override
        public String uploadImage(UUID tenantId, UUID productId, byte[] imageBytes, String variant) {
            return "http://localhost:9000/mercala-public-images/" + tenantId + "/" + productId + ".png";
        }

        @Override
        public byte[] readObject(String url) {
            lastRequestedUrl = url;
            if (failure != null) {
                throw failure;
            }
            return payload;
        }
    }

    private SourceImageLoader loader(StubStorage storage, long maxBytes) {
        return new SourceImageLoader(storage, maxBytes);
    }

    @Test
    void readsTheObjectThroughStorageRatherThanOverTheNetwork() {
        StubStorage storage = new StubStorage("a photograph".getBytes(), null);

        byte[] bytes = loader(storage, 15_728_640L).load(URL);

        assertThat(new String(bytes)).isEqualTo("a photograph");
        assertThat(storage.lastRequestedUrl).isEqualTo(URL);
    }

    @Test
    void aBlankUrlIsRejectedBeforeStorageIsTouched() {
        StubStorage storage = new StubStorage(new byte[0], null);

        assertThatThrownBy(() -> loader(storage, 15_728_640L).load("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source image URL");

        assertThat(storage.lastRequestedUrl).isNull();
    }

    /** A provider request is assembled in memory; an unbounded read decides how much. */
    @Test
    void anOversizedObjectIsRefusedRatherThanHandedToAProvider() {
        StubStorage storage = new StubStorage(new byte[2048], null);

        assertThatThrownBy(() -> loader(storage, 1024L).load(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over the");
    }

    @Test
    void anEmptyObjectIsAFailureNotAnEmptyEnhancement() {
        StubStorage storage = new StubStorage(new byte[0], null);

        assertThatThrownBy(() -> loader(storage, 15_728_640L).load(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    /** A refusal from the parser reaches the caller intact, rather than as a read failure. */
    @Test
    void aUrlStorageRefusesToResolveSurfacesAsSuch() {
        StubStorage storage = new StubStorage(
                null, new IllegalArgumentException("Image reference names an unknown bucket: other"));

        assertThatThrownBy(() -> loader(storage, 15_728_640L).load("http://localhost:9000/other/x.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown bucket");
    }
}
