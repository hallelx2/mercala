package com.mercala.media;

import java.util.List;
import java.util.UUID;

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
 */
@RestController
@RequestMapping("/api/products")
public class ProductImageController {

    private final ProductImageRepository productImages;

    public ProductImageController(ProductImageRepository productImages) {
        this.productImages = productImages;
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
                .findByTenantIdAndProductIdOrderByCreatedAtDesc(principal.tenantId(), productId)
                .stream()
                .map(image -> new ProductImageResponse(
                        image.getId(), image.getProductId(), image.getUrl(), image.getCreatedAt()))
                .toList();
    }
}
