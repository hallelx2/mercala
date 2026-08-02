package com.mercala.media;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.mercala.media.dto.UploadedMedia;
import com.mercala.platform.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Where a merchant's own photographs come in.
 *
 * <p>{@code POST /api/media/uploads} — multipart in, a media reference out. The reference is
 * what the agent is then handed to enhance, so this endpoint is the front door of the whole
 * image-enhancement path.
 *
 * <h2>What is checked, and why in this order</h2>
 *
 * <ol>
 *   <li><strong>Authentication and a store.</strong> The upload is written under the
 *       tenant's prefix, so a caller without a tenant has nowhere for it to go.</li>
 *   <li><strong>Size.</strong> Rejected before the bytes are examined — Spring has already
 *       buffered them by this point, and the multipart limit in configuration is the real
 *       defence; this is the message that explains it.</li>
 *   <li><strong>Magic bytes.</strong> Not the filename and not the declared content type,
 *       both of which the caller chooses freely. These objects are served back to browsers,
 *       so what is stored has to actually be an image.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/media")
public class MediaUploadController {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadController.class);

    private final MediaObjectStorage storage;
    private final MediaAssetRepository assets;
    private final long maxBytes;

    public MediaUploadController(
            MediaObjectStorage storage,
            MediaAssetRepository assets,
            @Value("${mercala.media.upload.max-bytes:10485760}") long maxBytes) {
        this.storage = storage;
        this.assets = assets;
        this.maxBytes = maxBytes;
    }

    @Operation(
            summary = "Upload an image the merchant owns",
            description = """
                    Accepts a JPEG, PNG, WebP or GIF and stores it under the caller's tenant
                    prefix. The returned URL is what an enhancement request names as its
                    source. Optionally attach it to a product at upload time.
                    """)
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UploadedMedia> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "productId", required = false) UUID productId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        if (principal == null || principal.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Create a store before uploading images");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded");
        }
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That image is %d MB. The limit is %d MB — try exporting it smaller."
                            .formatted(file.getSize() / (1024 * 1024), maxBytes / (1024 * 1024)));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.warn("Could not read an uploaded file from tenant {}", principal.tenantId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The upload could not be read");
        }

        ImageKind kind = ImageKind.detect(bytes);
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "That file is not a JPEG, PNG, WebP or GIF image");
        }

        String url;
        try {
            url = storage.put(principal.tenantId(), bytes, kind);
        } catch (MediaObjectStorage.MediaStorageException e) {
            log.error("Upload failed for tenant {}", principal.tenantId(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        MediaAsset asset = assets.save(new MediaAsset(
                principal.tenantId(),
                productId,
                url,
                kind.contentType(),
                bytes.length,
                file.getOriginalFilename(),
                principal.userId()));

        log.info("Merchant upload stored — tenant={}, asset={}, {} bytes",
                principal.tenantId(), asset.getId(), bytes.length);

        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadedMedia(
                asset.getId(), asset.getUrl(), asset.getContentType(),
                asset.getSizeBytes(), asset.getProductId()));
    }
}
