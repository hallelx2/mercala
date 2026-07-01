package com.mercala.catalog.service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.catalog.*;
import com.mercala.catalog.web.dto.*;
import com.mercala.identity.exception.ResourceConflictException;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.platform.multitenancy.TenantContext;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final VariantRepository variantRepository;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            VariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.variantRepository = variantRepository;
    }

    // --- Category Operations ---

    public CategoryResponse createCategory(CreateCategoryRequest request) {
        UUID tenantId = getRequiredTenantId();

        if (categoryRepository.existsByTenantIdAndSlug(tenantId, request.slug())) {
            throw new ResourceConflictException("Category slug already exists: " + request.slug());
        }

        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category not found"));
            if (!parent.getTenantId().equals(tenantId)) {
                throw new AccessDeniedException("Access denied to parent category from another tenant");
            }
        }

        Category category = new Category(tenantId, request.name(), request.slug(), parent);
        Category saved = categoryRepository.save(category);
        return mapToCategoryResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getCategories() {
        UUID tenantId = getRequiredTenantId();
        // Since @Filter restricts queries, we can just findAll and it will be scoped to tenant.
        // But to be completely robust, we fetch and map.
        return categoryRepository.findAll().stream()
                .filter(cat -> cat.getTenantId().equals(tenantId))
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    // --- Product Operations ---

    public ProductResponse createProduct(CreateProductRequest request) {
        UUID tenantId = getRequiredTenantId();

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
            if (!category.getTenantId().equals(tenantId)) {
                throw new AccessDeniedException("Access denied to category from another tenant");
            }
        }

        Product product = new Product(tenantId, request.name(), request.description(), request.price(), category);
        product.setStatus(ProductStatus.ACTIVE);

        if (request.variants() != null) {
            for (CreateVariantRequest vReq : request.variants()) {
                if (variantRepository.findBySku(vReq.sku()).isPresent()) {
                    throw new ResourceConflictException("Variant SKU already exists: " + vReq.sku());
                }
                Variant variant = new Variant(vReq.sku(), vReq.attrs(), vReq.price(), vReq.stockRef());
                product.addVariant(variant);
            }
        }

        Product saved = productRepository.save(product);
        return mapToProductResponse(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        return mapToProductResponse(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getProducts() {
        UUID tenantId = getRequiredTenantId();
        return productRepository.findByTenantId(tenantId).stream()
                .map(this::mapToProductResponse)
                .collect(Collectors.toList());
    }

    public ProductResponse updateProduct(UUID productId, UpdateProductRequest request) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStatus(request.status());

        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
            if (!category.getTenantId().equals(tenantId)) {
                throw new AccessDeniedException("Access denied to category from another tenant");
            }
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }

        Product saved = productRepository.save(product);
        return mapToProductResponse(saved);
    }

    public void deleteProduct(UUID productId) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        productRepository.delete(product);
    }

    // --- Variant Operations ---

    public VariantResponse addVariant(UUID productId, CreateVariantRequest request) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        if (variantRepository.findBySku(request.sku()).isPresent()) {
            throw new ResourceConflictException("Variant SKU already exists: " + request.sku());
        }

        Variant variant = new Variant(request.sku(), request.attrs(), request.price(), request.stockRef());
        product.addVariant(variant);
        productRepository.save(product);

        // Retrieve the saved variant with generated ID
        Variant savedVariant = product.getVariants().stream()
                .filter(v -> v.getSku().equals(request.sku()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Variant could not be saved"));

        return mapToVariantResponse(savedVariant);
    }

    public VariantResponse updateVariant(UUID productId, UUID variantId, UpdateVariantRequest request) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        Variant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found: " + variantId));

        if (variant.getProduct() == null || !variant.getProduct().getId().equals(product.getId())) {
            throw new ResourceNotFoundException("Variant does not belong to the specified product");
        }

        if (!variant.getSku().equals(request.sku())) {
            var existing = variantRepository.findBySku(request.sku());
            if (existing.isPresent() && !existing.get().getId().equals(variantId)) {
                throw new ResourceConflictException("Variant SKU already exists: " + request.sku());
            }
        }

        variant.setSku(request.sku());
        variant.setPrice(request.price());
        variant.setAttrs(request.attrs());
        variant.setStockRef(request.stockRef());

        Variant saved = variantRepository.save(variant);
        return mapToVariantResponse(saved);
    }

    public void deleteVariant(UUID productId, UUID variantId) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        Variant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant not found: " + variantId));

        if (variant.getProduct() == null || !variant.getProduct().getId().equals(product.getId())) {
            throw new ResourceNotFoundException("Variant does not belong to the specified product");
        }

        product.removeVariant(variant);
        productRepository.save(product);
    }

    // --- Helper Mappers ---

    private UUID getRequiredTenantId() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Tenant context is required");
        }
        return tenantId;
    }

    private CategoryResponse mapToCategoryResponse(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug());
    }

    private VariantResponse mapToVariantResponse(Variant variant) {
        if (variant == null) {
            return null;
        }
        return new VariantResponse(
                variant.getId(),
                variant.getSku(),
                variant.getPrice(),
                variant.getAttrs(),
                variant.getStockRef(),
                variant.getCreatedAt(),
                variant.getUpdatedAt()
        );
    }

    private ProductResponse mapToProductResponse(Product product) {
        if (product == null) {
            return null;
        }
        List<VariantResponse> varResponses = product.getVariants().stream()
                .map(this::mapToVariantResponse)
                .collect(Collectors.toList());

        return new ProductResponse(
                product.getId(),
                product.getTenantId(),
                product.getName(),
                product.getDescription(),
                product.getStatus(),
                product.getPrice(),
                mapToCategoryResponse(product.getCategory()),
                varResponses,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
