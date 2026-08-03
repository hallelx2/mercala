package com.mercala.media;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.media.dto.ProductImageResponse;
import com.mercala.platform.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Reading a product's imagery back.
 *
 * <p>Images are produced asynchronously: the agent queues a job, Kafka carries it, the
 * worker calls a provider, and a result lands seconds later. Nothing in the API let the
 * merchant see the outcome — {@code ProductResponse} carries no images — so generated and
 * enhanced pictures existed only in a bucket. That is the gap that made "watch your photo
 * being retouched" impossible to build honestly, and this closes it.
 *
 * <p>Under {@code /api/products} rather than {@code /api/media} deliberately: the media
 * prefix is blanket-permitted for anonymous callers (HAL-495), and a product's imagery is
 * a merchant's own data.
 *
 * <p>Each image comes back twice: the stored URL, which is stable and is what a client
 * should key on, and a presigned one it can actually load. The bucket is private and stays
 * that way — it holds the Terraform state, the TLS archive and the database backups
 * alongside the pictures (HAL-425).
 */
@RestController
@RequestMapping("/api/products")
public class ProductImageController {

    private static final Logger log = LoggerFactory.getLogger(ProductImageController.class);

    /**
     * Long enough to load a dashboard and sit looking at it; short enough that a URL
     * copied out of devtools is not a lasting grant.
     */
    private static final Duration VIEW_TTL = Duration.ofMinutes(15);

    private final ProductImageRepository productImages;
    private final MediaObjectStorage storage;

    public ProductImageController(ProductImageRepository productImages, MediaObjectStorage storage) {
        this.productImages = productImages;
        this.storage = storage;
    }

    @Operation(
            summary = "List a product's images",
            description = """
                    Newest first. Tenant-scoped by the Hibernate filter and by row-level
                    security, so a product id belonging to another store returns an empty
                    list rather than someone else's pictures.
                    """)
    @GetMapping("/{productId}/images")
    @Transactional(readOnly = true)
    public List<ProductImageResponse> list(
            @PathVariable UUID productId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        if (principal == null || principal.tenantId() == null) {
            return List.of();
        }

        return productImages
                .findByTenantIdAndProductIdOrderByCreatedAtDescIdDesc(principal.tenantId(), productId)
                .stream()
                .map(image -> new ProductImageResponse(
                        image.getId(),
                        image.getProductId(),
                        image.getUrl(),
                        presign(image.getUrl()),
                        image.getCreatedAt()))
                .toList();
    }

    /**
     * The stored URL points into a private bucket, so a browser given it gets a 403. This
     * is what makes the picture actually appear: a signature valid for long enough to load
     * the page and look at it, minted here because the caller has already been
     * authenticated and scoped to their own tenant by the query above.
     *
     * <p>A failure is a null rather than a 500. Storage being unreachable should cost the
     * merchant their thumbnails, not their product page.
     */
    private String presign(String url) {
        try {
            return storage.presignedView(storage.objectKeyOf(url), VIEW_TTL);
        } catch (RuntimeException e) {
            log.warn("Could not presign {}: {}", url, e.getMessage());
            return null;
        }
    }
}
