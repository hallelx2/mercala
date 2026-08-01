package com.mercala.imagegen.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the keyless-runtime contract.
 *
 * <p>Regression test for a real defect: the deployed Compose template dropped
 * {@code MINIO_ACCESS_KEY} and {@code MINIO_SECRET_KEY} entirely, expecting that to mean
 * "no credentials". It does not — {@code application.yml} defaults them to
 * {@code minioadmin} for the local MinIO container, so the deployed service would have
 * authenticated to real S3 as {@code minioadmin} and never touched the instance profile,
 * silently defeating the whole point of the IAM role.
 */
class MinioStorageCredentialSelectionTest {

    private static MinioStorageService service(String endpoint, String accessKey, String secretKey) {
        return new MinioStorageService(endpoint, accessKey, secretKey, "", "us-east-1", "mercala-images");
    }

    @Test
    void treatsLocalMinioDefaultsAgainstAwsAsAMisconfiguration() {
        MinioStorageService s = service("https://s3.us-east-1.amazonaws.com", "minioadmin", "minioadmin");

        assertThat(s.isLocalDevCredentialAgainstRealS3())
                .as("local dev defaults pointed at real S3 must not be used as credentials")
                .isTrue();
    }

    @Test
    void leavesGenuineLocalMinioAlone() {
        MinioStorageService s = service("http://localhost:9000", "minioadmin", "minioadmin");

        assertThat(s.isLocalDevCredentialAgainstRealS3())
                .as("local development must keep working unchanged")
                .isFalse();
    }

    @Test
    void leavesRealCredentialsAgainstAwsAlone() {
        MinioStorageService s = service("https://s3.us-east-1.amazonaws.com", "AKIAIOSFODNN7EXAMPLE", "secret");

        assertThat(s.isLocalDevCredentialAgainstRealS3())
                .as("a deliberately configured IAM user must still be honoured")
                .isFalse();
    }

    @Test
    void blankCredentialsAgainstAwsAreNotFlagged() {
        // The correct deployed configuration: blank credentials, instance profile used.
        MinioStorageService s = service("https://s3.us-east-1.amazonaws.com", "", "");

        assertThat(s.isLocalDevCredentialAgainstRealS3()).isFalse();
    }

    @Test
    void detectionIsCaseInsensitiveOnTheEndpoint() {
        MinioStorageService s = service("https://S3.US-EAST-1.AMAZONAWS.COM", "minioadmin", "minioadmin");

        assertThat(s.isLocalDevCredentialAgainstRealS3()).isTrue();
    }
}
