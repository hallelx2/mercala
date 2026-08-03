package com.mercala.media;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.credentials.IamAwsProvider;
import io.minio.http.Method;
import io.minio.credentials.StaticProvider;
import jakarta.annotation.PostConstruct;

/**
 * Stores merchant uploads in the private bucket, and only there.
 *
 * <p>Uploads are the merchant's raw material, not published work: an enhancement they
 * reject should not stay world-readable forever. So they sit beside the Terraform state,
 * the Let's Encrypt archive and the nightly database dump, in a bucket with Block Public
 * Access fully on. The image worker reads them with credentials, and the dashboard views
 * them through a presigned URL that expires. Finished product imagery goes somewhere else
 * entirely — a second, public-read bucket that contains nothing but pictures meant to be
 * seen (HAL-425).
 *
 * <p>Credential resolution mirrors {@code mercala-image-gen}'s storage service — static
 * key and session token, static key and secret, or the host's IAM instance profile — because
 * the two processes run against the same endpoint under the same deployment rules. That the
 * logic exists twice is a real duplication, tracked as HAL-567.
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
     * A short-lived URL a browser can use to view a private object.
     *
     * <p>Merchant uploads live in the private bucket and stay there: they are the raw
     * material, and an enhancement the merchant rejects would otherwise remain
     * world-readable forever. So the dashboard cannot link to them directly, and this is
     * how it shows them — a signature that expires, minted per request for a caller who
     * has already been authenticated and checked against the object's tenant prefix.
     *
     * @param objectKey the key inside the private bucket, already checked by the caller
     */
    public String presignedView(String objectKey, Duration ttl) {
        if (minioClient == null) {
            throw new MediaStorageException("Image storage is not available right now");
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .build());
        } catch (Exception e) {
            log.error("Could not presign {}", objectKey, e);
            throw new MediaStorageException("Could not open that image", e);
        }
    }

    /**
     * Recovers the object key from a URL this service produced.
     *
     * <p>Path-style only, which is the single shape {@link #put} writes. The caller is
     * responsible for deciding whether the key belongs to them — this only establishes
     * that the URL names an object in <em>this</em> bucket, and refuses everything else
     * before any credentialed call is made.
     *
     * <p>The same parsing exists in {@code mercala-image-gen}'s {@code ObjectRef}; both
     * disappear when the storage client is extracted (HAL-567).
     */
    public String objectKeyOf(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("An image reference is required");
        }
        String prefix = trimSlash(endpoint) + "/" + bucket + "/";
        if (!url.trim().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("That image does not belong to this store's uploads");
        }
        String key = url.trim().substring(prefix.length());
        if (key.isBlank() || key.contains("..")) {
            throw new IllegalArgumentException("That image reference names no object");
        }
        return key;
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

    private static String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
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
