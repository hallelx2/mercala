package com.mercala.catalog.web;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mercala.catalog.ProductStatus;
import com.mercala.catalog.service.ProductService;
import com.mercala.catalog.web.dto.ProductResponse;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.multitenancy.TenantContext;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;

/**
 * The storefront's read-only API — the only anonymous window into a store.
 *
 * <p>Everything else in the API derives its tenant from the JWT; a shopper who
 * has not signed in has no JWT, so here the tenant comes from the store slug in
 * the path. The controller resolves the slug and installs the tenant into
 * {@link TenantContext} for the request, after which the existing service layer
 * — Hibernate tenant filter and Postgres RLS included — behaves exactly as it
 * does for authenticated calls. The isolation layers do not know or care where
 * the tenant id came from.
 *
 * <p>Strictly GET, strictly ACTIVE products. Draft and archived products do not
 * exist as far as this surface is concerned, and their ids return the same 404
 * as a genuinely absent product so the status of an unpublished item leaks
 * nothing.
 */
@SecurityRequirements // Public by design: a storefront no one can open sells nothing.
@RestController
@RequestMapping("/api/public/stores/{slug}")
public class PublicStoreController {

    private final TenantRepository tenantRepository;
    private final ProductService productService;

    public PublicStoreController(TenantRepository tenantRepository, ProductService productService) {
        this.tenantRepository = tenantRepository;
        this.productService = productService;
    }

    /** Public store profile: name and what it sells. */
    public record PublicStoreResponse(String slug, String name, String description) {}

    @GetMapping
    public PublicStoreResponse profile(@PathVariable String slug) {
        Tenant tenant = resolve(slug);
        return new PublicStoreResponse(tenant.getSlug(), tenant.getName(), tenant.getDescription());
    }

    @GetMapping("/products")
    public Page<ProductResponse> products(
            @PathVariable String slug,
            @PageableDefault(size = 24) Pageable pageable) {
        return withTenant(slug, () -> productService.getActiveProducts(pageable));
    }

    @GetMapping("/products/{id}")
    public ProductResponse product(@PathVariable String slug, @PathVariable UUID id) {
        return withTenant(slug, () -> {
            ProductResponse product = productService.getProduct(id);
            if (product.status() != ProductStatus.ACTIVE) {
                // Same 404 as a missing product — an unpublished item's existence
                // is not public information.
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
            }
            return product;
        });
    }

    @GetMapping("/search")
    public Page<ProductResponse> search(
            @PathVariable String slug,
            @RequestParam String q,
            @PageableDefault(size = 24) Pageable pageable) {
        return withTenant(slug, () -> productService.searchHybrid(q, pageable));
    }

    private Tenant resolve(String slug) {
        return tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
    }

    /**
     * Installs the slug's tenant for the duration of the call and always clears
     * it after — this request's thread must not bleed a tenant into the pool.
     */
    private <T> T withTenant(String slug, java.util.function.Supplier<T> body) {
        Tenant tenant = resolve(slug);
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            return body.get();
        } finally {
            TenantContext.clear();
        }
    }
}
