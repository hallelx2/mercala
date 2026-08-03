package com.mercala.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning a stored URL back into an object key, before anything is signed.
 *
 * <p>The URL comes in on a query parameter, so it is caller-controlled. Establishing that
 * it names an object in <em>this</em> bucket is the first of two checks; the second — that
 * the key belongs to the caller's tenant — lives in {@link MediaViewController}, because
 * only the request knows who is asking.
 */
class MediaObjectStorageKeyTest {

    private static final String ENDPOINT = "http://localhost:9000";
    private static final String BUCKET = "mercala-images";

    private static MediaObjectStorage storage() {
        return new MediaObjectStorage(ENDPOINT, "minioadmin", "minioadmin", "", "", BUCKET);
    }

    @Test
    void recoversTheKeyFromAUrlThisServiceWrote() {
        String key = storage().objectKeyOf(ENDPOINT + "/" + BUCKET + "/tenant-1/uploads/photo.png");

        assertThat(key).isEqualTo("tenant-1/uploads/photo.png");
    }

    @Test
    void aUrlFromAnotherHostIsRefused() {
        assertThatThrownBy(() -> storage().objectKeyOf("https://evil.example/" + BUCKET + "/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The public bucket is not readable through the presigning path — it needs no signature. */
    @Test
    void aUrlNamingADifferentBucketIsRefused() {
        assertThatThrownBy(() -> storage().objectKeyOf(ENDPOINT + "/mercala-public-images/t/p.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTraversalSegmentIsRefused() {
        assertThatThrownBy(() -> storage().objectKeyOf(ENDPOINT + "/" + BUCKET + "/../backups/db.sql.gz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUrlNamingNoObjectIsRefused() {
        assertThatThrownBy(() -> storage().objectKeyOf(ENDPOINT + "/" + BUCKET + "/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage().objectKeyOf("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
