package com.mercala.catalog.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.catalog.service.ProductService;
import com.mercala.media.ProductImageDecorator;
import com.mercala.catalog.web.dto.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductImageDecorator images;

    public ProductController(ProductService productService, ProductImageDecorator images) {
        this.productService = productService;
        this.images = images;
    }

    // --- Product CRUD Endpoints ---

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public ProductResponse createProduct(@Valid @RequestBody CreateProductRequest request) {
        return productService.createProduct(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF') or hasRole('SHOPPER')")
    public ProductResponse getProduct(@PathVariable UUID id) {
        return images.decorate(productService.getProduct(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF') or hasRole('SHOPPER')")
    public Page<ProductResponse> getProducts(
            @PageableDefault(size = 20) Pageable pageable) {
        // The merchant's own catalogue had the same blind spot as the storefront: it could
        // list products and never show what any of them looked like.
        return images.decorate(productService.getProducts(pageable));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public ProductResponse updateProduct(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public void deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
    }

    // --- Product Variant Sub-Resource Endpoints ---

    @PostMapping("/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public VariantResponse addVariant(@PathVariable UUID id, @Valid @RequestBody CreateVariantRequest request) {
        return productService.addVariant(id, request);
    }

    @PutMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public VariantResponse updateVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody UpdateVariantRequest request) {
        return productService.updateVariant(productId, variantId, request);
    }

    @DeleteMapping("/{productId}/variants/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF')")
    public void deleteVariant(@PathVariable UUID productId, @PathVariable UUID variantId) {
        productService.deleteVariant(productId, variantId);
    }

    @PutMapping("/{id}/embedding")
    @PreAuthorize("hasRole('MERCHANT_OWNER') or hasRole('MERCHANT_STAFF') or hasRole('SHOPPER') or isAuthenticated()")
    public void updateEmbedding(@PathVariable UUID id, @RequestBody List<Double> embedding) {
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).floatValue();
        }
        productService.updateEmbedding(id, vector);
    }
}
