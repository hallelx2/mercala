package com.mercala.imagegen.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.util.UUID;

@Service
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    
    private MinioClient minioClient;

    public MinioStorageService(
            @Value("${mercala.storage.endpoint}") String endpoint,
            @Value("${mercala.storage.access-key}") String accessKey,
            @Value("${mercala.storage.secret-key}") String secretKey,
            @Value("${mercala.storage.bucket}") String bucket) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing MinIO Client for endpoint: {}", endpoint);
            this.minioClient = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();

            // Ensure bucket exists
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build()
            );
            if (!exists) {
                log.info("Creating storage bucket: {}", bucket);
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build()
                );
            }

            // Always ensure the public policy is applied
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
            log.error("Failed to initialize MinIO Client / bucket policy", e);
            throw new RuntimeException("MinioStorageService initialization failed", e);
        }
    }

    @Override
    public String uploadImage(UUID tenantId, UUID productId, byte[] imageBytes) {
        String objectName = tenantId.toString() + "/" + productId.toString() + ".png";
        try {
            log.info("Uploading image to bucket={} object={}", bucket, objectName);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .stream(bais, imageBytes.length, -1)
                                .contentType("image/png")
                                .build()
                );
            }
            String url = endpoint + "/" + bucket + "/" + objectName;
            log.info("Successfully uploaded image. URL: {}", url);
            return url;
        } catch (Exception e) {
            log.error("Failed to upload image to MinIO/S3", e);
            throw new RuntimeException("Image upload failure", e);
        }
    }
}
