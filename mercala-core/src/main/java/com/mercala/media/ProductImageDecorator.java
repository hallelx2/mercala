package com.mercala.media;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.catalog.web.dto.ProductImageView;
import com.mercala.catalog.web.dto.ProductResponse;
import com.mercala.platform.multitenancy.TenantContext;

/**
 * Attaches imagery to products on their way out.
 *
 * <h2>Why here and not in the product mapper</h2>
 *
 * <p>Two reasons, and the second is the load-bearing one. Images live in the media module,
 * and the catalogue service has no business knowing that object storage exists. More
 * concretely: the mapper runs once per product, so resolving imagery inside it would issue
 * one query per row — twenty-four on a storefront page, plus a presign each. Batching at
 * the edge turns that into a single {@code IN} query for the whole page.
 *
 * <p>Presigning is local HMAC computation rather than a call to storage, so signing a page
 * of images is arithmetic, not latency.
 *
 * <h2>Tenancy</h2>
 *
 * <p>The lookup names the tenant explicitly rather than leaning on the Hibernate filter.
 * The filter is enabled per transaction by an aspect, and this runs behind read paths that
 * may or may not have one — including the anonymous storefront, where the tenant comes from
 * a slug rather than a token. Naming it makes the scope independent of that timing, and
 * leaves the filter and RLS as the second and third layers rather than the only ones.
 */
@Component
public class ProductImageDecorator {

    private static final Logger log = LoggerFactory.getLogger(ProductImageDecorator.class);

    private final ProductImageRepository productImages;
    private final MediaObjectStorage storage;
    private final Duration viewTtl;

    public ProductImageDecorator(
            ProductImageRepository productImages,
            MediaObjectStorage storage,
            @Value("${mercala.media.view-ttl:PT1H}") Duration viewTtl) {
        this.productImages = productImages;
        this.storage = storage;
        this.viewTtl = viewTtl;
    }

    /** One product, one query. */
    @Transactional(readOnly = true)
    public ProductResponse decorate(ProductResponse product) {
        if (product == null) {
            return null;
        }
        return product.withImages(imagesFor(List.of(product.id())).getOrDefault(product.id(), List.of()));
    }

    /** A whole page, still one query. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> decorate(Page<ProductResponse> products) {
        if (products.isEmpty()) {
            return products;
        }
        Map<UUID, List<ProductImageView>> byProduct = imagesFor(
                products.getContent().stream().map(ProductResponse::id).toList());

        return products.map(product ->
                product.withImages(byProduct.getOrDefault(product.id(), List.of())));
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> decorate(List<ProductResponse> products) {
        if (products.isEmpty()) {
            return products;
        }
        Map<UUID, List<ProductImageView>> byProduct = imagesFor(
                products.stream().map(ProductResponse::id).toList());

        return products.stream()
                .map(product -> product.withImages(byProduct.getOrDefault(product.id(), List.of())))
                .toList();
    }

    private Map<UUID, List<ProductImageView>> imagesFor(Collection<UUID> productIds) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || productIds.isEmpty()) {
            return Map.of();
        }

        return productImages
                .findByTenantIdAndProductIdInOrderByCreatedAtDescIdDesc(tenantId, productIds)
                .stream()
                .collect(Collectors.groupingBy(
                        ProductImage::getProductId,
                        Collectors.mapping(this::toView, Collectors.toList())));
    }

    /**
     * The stored URL stays as the image's identity; the signed one is what loads. A
     * signature that cannot be minted is a null rather than an exception — storage being
     * unreachable should cost a shopper the picture, not the page it is on.
     */
    private ProductImageView toView(ProductImage image) {
        try {
            return new ProductImageView(
                    image.getUrl(), storage.presignedView(storage.objectKeyOf(image.getUrl()), viewTtl));
        } catch (RuntimeException e) {
            log.warn("Could not presign {}: {}", image.getUrl(), e.getMessage());
            return new ProductImageView(image.getUrl(), null);
        }
    }
}
