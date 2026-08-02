package com.mercala.media;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.StaticProvider;
import jakarta.annotation.PostConstruct;

/**
 * Stores merchant uploads in the same bucket the image worker writes to.
 *
 * <p>One bucket on purpose: an enhancement reads the merchant's original and writes its
 * result, and both have to be reachable by the same worker with the same credentials.
 * Splitting them would mean a second bucket policy, a second set of grants, and a second
 * thing to get wrong.
 *
 * <p>Credential resolution mirrors {@code mercala-image-gen}'s storage service — static
 * key and session token, static key and secret, or the host's IAM instance profile — because
 * the two processes run against the same endpoint under the same deployment rules. That the
 * logic exists twice is a real duplication and is tracked as its own issue; inlining it here
 * was the smaller of the two costs while this feature landed.
 */
@Service
public class MediaObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MediaObjectStorage.class);

    /** The local MinIO container's default access key, as set in docker-compose.yml. */
    private static final String LOCAL_DEV_ACCESS_KEY = "minioadmin";

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final String region;
    private final String bucket;

    private MinioClient minioClient;

    public MediaObjectStorage(
            @Value("${mercala.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${mercala.storage.access-key:}") String accessKey,
            @Value("${mercala.storage.secret-key:}") String secretKey,
            @Value("${mercala.storage.session-token:}") String sessionToken,
            @Value("${mercala.storage.region:}") String region,
            @Value("${mercala.storage.bucket:mercala-images}") String bucket) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.region = region;
        this.bucket = bucket;
    }

    /**
     * Storage problems must not stop the application from starting. Core serves the whole
     * API; refusing to boot because object storage is unreachable would take checkout,
     * search and auth down over a feature that only some merchants use. Uploads then fail
     * with a clear message instead.
     */
    @PostConstruct
    void init() {
        try {
            MinioClient.Builder builder = MinioClient.builder().endpoint(endpoint);
            if (hasText(region)) {
                builder.region(region);
            }
            applyCredentials(builder);
            this.minioClient = builder.build();
            ensureBucketExists();
            log.info("Media object storage ready — endpoint={}, bucket={}", endpoint, bucket);
        } catch (Exception e) {
            log.error("Media object storage is unavailable at {} — uploads will be refused until it is fixed",
                    endpoint, e);
            this.minioClient = null;
        }
    }

    public boolean isReady() {
        return minioClient != null;
    }

    /**
     * @return the URL the stored object can be read back from
     */
    public String put(UUID tenantId, byte[] bytes, ImageKind kind) {
        if (minioClient == null) {
            throw new MediaStorageException("Image storage is not available right now");
        }

        // Tenant-prefixed, because the bucket is shared and the prefix is what makes an
        // object's owner legible from its name alone.
        String objectName = tenantId + "/uploads/" + UUID.randomUUID() + "." + kind.extension();

        try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(stream, bytes.length, -1)
                    .contentType(kind.contentType())
                    .build());
        } catch (Exception e) {
            log.error("Failed to store a {} byte upload for tenant {}", bytes.length, tenantId, e);
            throw new MediaStorageException("Could not store the uploaded image", e);
        }

        String url = endpoint + "/" + bucket + "/" + objectName;
        log.info("Stored a {} byte {} upload for tenant {} at {}", bytes.length, kind, tenantId, url);
        return url;
    }

    private void applyCredentials(MinioClient.Builder builder) {
        if (isLocalDevCredentialAgainstRealS3()) {
            log.warn("Ignoring local MinIO development credentials against the AWS S3 endpoint {} — "
                    + "resolving from the IAM instance profile instead.", endpoint);
            builder.credentialsProvider(new IamAwsProvider(null, null));
            return;
        }

        if (hasText(accessKey) && hasText(secretKey)) {
            if (hasText(sessionToken)) {
                builder.credentialsProvider(new StaticProvider(accessKey, secretKey, sessionToken));
            } else {
                builder.credentials(accessKey, secretKey);
            }
            return;
        }

        if (hasText(accessKey) != hasText(secretKey)) {
            log.warn("Only one of the storage access key / secret key is set — ignoring the partial pair "
                    + "and resolving from the IAM instance profile.");
        }
        builder.credentialsProvider(new IamAwsProvider(null, null));
    }

    private void ensureBucketExists() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            log.info("Creating storage bucket: {}", bucket);
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * "minioadmin" is the local container's default and can never be a real AWS access key
     * id, so seeing it pointed at an AWS endpoint is a leak of local defaults rather than a
     * deliberate choice.
     */
    boolean isLocalDevCredentialAgainstRealS3() {
        return endpoint != null
                && endpoint.toLowerCase(Locale.ROOT).contains("amazonaws.com")
                && LOCAL_DEV_ACCESS_KEY.equals(accessKey == null ? null : accessKey.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** Thrown when storage cannot accept an upload; surfaced to the caller as a 503. */
    public static class MediaStorageException extends RuntimeException {

        public MediaStorageException(String message) {
            super(message);
        }

        public MediaStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
