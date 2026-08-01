package com.mercala.imagegen.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.credentials.IamAwsProvider;
import io.minio.credentials.StaticProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.UUID;

@Service
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    /** The local MinIO container's default access key, as set in docker-compose.yml. */
    private static final String LOCAL_DEV_ACCESS_KEY = "minioadmin";

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final String region;
    private final String bucket;

    private MinioClient minioClient;

    public MinioStorageService(
            @Value("${mercala.storage.endpoint}") String endpoint,
            @Value("${mercala.storage.access-key:}") String accessKey,
            @Value("${mercala.storage.secret-key:}") String secretKey,
            @Value("${mercala.storage.session-token:}") String sessionToken,
            @Value("${mercala.storage.region:}") String region,
            @Value("${mercala.storage.bucket}") String bucket) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.region = region;
        this.bucket = bucket;
    }

    @PostConstruct
    public void init() {
        try {
            MinioClient.Builder builder = MinioClient.builder().endpoint(endpoint);

            if (hasText(region)) {
                builder.region(region);
            }

            applyCredentials(builder);
            this.minioClient = builder.build();

            ensureBucketExists();
            applyPublicReadPolicy();
        } catch (Exception e) {
            log.error("Failed to initialize storage client for endpoint {}", endpoint, e);
            throw new RuntimeException("MinioStorageService initialization failed", e);
        }
    }

    /**
     * Three credential modes, in order of specificity:
     *
     * <ol>
     *   <li>Static key + session token — temporary STS credentials, passed explicitly.</li>
     *   <li>Static key + secret — a local MinIO container, or a long-lived IAM user.</li>
     *   <li>Neither — resolve from the AWS instance metadata service. This is the
     *       deployed path: the host carries the {@code mercala-app-host} instance
     *       profile, so no credentials are configured anywhere and nothing expires.</li>
     * </ol>
     */
    private void applyCredentials(MinioClient.Builder builder) {
        if (isLocalDevCredentialAgainstRealS3()) {
            // The deployed Compose template must set these blank so the instance profile
            // is used. If it ever omits them instead, application.yml's local-MinIO
            // defaults apply and the container would authenticate to real S3 as
            // "minioadmin" — which fails confusingly rather than falling back. Ignoring
            // them here turns a silent misconfiguration into a warning plus the correct
            // behaviour.
            log.warn("Ignoring local MinIO development credentials against the AWS S3 endpoint {} — "
                    + "resolving from the IAM instance profile instead. Set MINIO_ACCESS_KEY and "
                    + "MINIO_SECRET_KEY to empty strings in the deployed environment.", endpoint);
            builder.credentialsProvider(new IamAwsProvider(null, null));
            return;
        }

        if (hasText(accessKey) && hasText(secretKey)) {
            if (hasText(sessionToken)) {
                log.info("Storage client using static credentials with session token, endpoint={}", endpoint);
                builder.credentialsProvider(new StaticProvider(accessKey, secretKey, sessionToken));
            } else {
                log.info("Storage client using static credentials, endpoint={}", endpoint);
                builder.credentials(accessKey, secretKey);
            }
            return;
        }

        // Exactly one of the pair being set is always a mistake — a typo or a half-filled
        // environment. Falling through silently would make it look like the instance
        // profile was chosen deliberately, so say so.
        if (hasText(accessKey) != hasText(secretKey)) {
            log.warn("Only one of the storage access key / secret key is set — ignoring the partial pair "
                    + "and resolving from the IAM instance profile. This is almost certainly a "
                    + "configuration error.");
        } else {
            log.info("No static storage credentials configured — resolving from the IAM instance profile, endpoint={}", endpoint);
        }
        builder.credentialsProvider(new IamAwsProvider(null, null));
    }

    /**
     * In the deployed stack Terraform already created the bucket, so this is a no-op
     * check. It matters for local MinIO, which starts empty.
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            log.info("Creating storage bucket: {}", bucket);
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * Local MinIO needs an explicit public-read policy for generated images to be
     * fetchable by the storefront. On real S3 this is expected to fail — the bucket
     * has Block Public Access on — so the failure is logged and tolerated rather than
     * being allowed to kill startup.
     */
    private void applyPublicReadPolicy() {
        try {
            String policyJson = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetObject"],
                          "Resource": ["arn:aws:s3:::%s/*"]
                        }
                      ]
                    }
                    """.formatted(bucket);
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(bucket).config(policyJson).build()
            );
        } catch (Exception e) {
            log.warn("Could not set public-read bucket policy on {} (expected on S3 with Block Public Access): {}",
                    bucket, e.getMessage());
        }
    }

    @Override
    public String uploadImage(UUID tenantId, UUID productId, byte[] imageBytes) {
        // Providers disagree on output format, so derive it from the bytes instead of
        // assuming PNG. Storing a WebP as .png with an image/png content type produces a
        // file some browsers refuse to render.
        ImageFormat format = ImageFormat.detect(imageBytes);
        String objectName = tenantId + "/" + productId + "." + format.extension();

        try {
            log.info("Uploading {} image to bucket={} object={}", format, bucket, objectName);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .stream(bais, imageBytes.length, -1)
                                .contentType(format.contentType())
                                .build()
                );
            }
            String url = endpoint + "/" + bucket + "/" + objectName;
            log.info("Successfully uploaded image. URL: {}", url);
            return url;
        } catch (Exception e) {
            log.error("Failed to upload image to storage", e);
            throw new RuntimeException("Image upload failure", e);
        }
    }

    /**
     * "minioadmin" is the local MinIO container's default and can never be a real AWS
     * access key id, so seeing it pointed at an AWS endpoint is unambiguously a
     * configuration leak from local defaults rather than a deliberate choice.
     */
    boolean isLocalDevCredentialAgainstRealS3() {
        return endpoint != null
                && endpoint.toLowerCase(Locale.ROOT).contains("amazonaws.com")
                && LOCAL_DEV_ACCESS_KEY.equals(accessKey == null ? null : accessKey.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
