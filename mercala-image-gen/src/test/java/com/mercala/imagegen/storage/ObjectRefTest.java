package com.mercala.imagegen.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This is the check that used to be an HTTP allow-list.
 *
 * <p>The URL arrives on a Kafka event that originated in a chat message. It is now used to
 * name an object rather than to make a request, which removes the SSRF question entirely —
 * but it can still name the wrong bucket, and these are the cases that say it cannot.
 */
class ObjectRefTest {

    private static final String ENDPOINT = "https://s3.us-east-1.amazonaws.com";
    private static final String PRIVATE = "mercala-media-storage-us-east-1";
    private static final String PUBLIC = "mercala-public-media-us-east-1";

    private static ObjectRef parse(String url) {
        return ObjectRef.parse(url, ENDPOINT, PRIVATE, PUBLIC);
    }

    @Test
    void splitsAUrlThisSystemWroteIntoItsBucketAndKey() {
        ObjectRef ref = parse(ENDPOINT + "/" + PRIVATE + "/tenant-1/uploads/photo.jpg");

        assertThat(ref.bucket()).isEqualTo(PRIVATE);
        assertThat(ref.key()).isEqualTo("tenant-1/uploads/photo.jpg");
    }

    @Test
    void bothOfThisDeploymentsBucketsAreAccepted() {
        assertThat(parse(ENDPOINT + "/" + PUBLIC + "/tenant-1/product.png").bucket()).isEqualTo(PUBLIC);
    }

    @Test
    void aBucketThatIsNotOursIsRefused() {
        assertThatThrownBy(() -> parse(ENDPOINT + "/someone-elses-bucket/secrets.sql.gz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown bucket");
    }

    @Test
    void aDifferentHostIsRefused() {
        assertThatThrownBy(() -> parse("https://evil.example/" + PRIVATE + "/tenant-1/photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mercala storage");
    }

    /** The classic near-miss: our endpoint present, but not at the front. */
    @Test
    void aUrlThatOnlyMentionsTheEndpointLaterIsRefused() {
        assertThatThrownBy(() -> parse("https://evil.example/?next=" + ENDPOINT + "/" + PRIVATE + "/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTraversalSegmentIsRefusedRatherThanNormalised() {
        assertThatThrownBy(() -> parse(ENDPOINT + "/" + PRIVATE + "/tenant-1/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void aUrlNamingNoObjectIsRefused() {
        assertThatThrownBy(() -> parse(ENDPOINT + "/" + PRIVATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(ENDPOINT + "/" + PRIVATE + "/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * With no endpoint configured there is nothing to compare against, so the URL could
     * name any bucket the host's credentials reach. Failing closed costs the feature;
     * failing open costs the bucket.
     */
    @Test
    void withNoEndpointConfiguredNothingParses() {
        assertThatThrownBy(() -> ObjectRef.parse(ENDPOINT + "/" + PRIVATE + "/x.png", "", PRIVATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No storage endpoint");
    }

    @Test
    void aTrailingSlashOnTheEndpointDoesNotChangeTheAnswer() {
        ObjectRef ref = ObjectRef.parse(
                ENDPOINT + "/" + PRIVATE + "/tenant-1/photo.jpg", ENDPOINT + "/", PRIVATE);

        assertThat(ref.key()).isEqualTo("tenant-1/photo.jpg");
    }

    @Test
    void aBlankReferenceIsRefused() {
        assertThatThrownBy(() -> parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
