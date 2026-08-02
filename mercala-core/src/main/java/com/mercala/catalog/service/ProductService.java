package com.mercala.catalog.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.catalog.*;
import com.mercala.catalog.events.ProductAdded;
import com.mercala.catalog.events.ProductUpdated;
import com.mercala.catalog.ports.EmbeddingPort;
import com.mercala.catalog.web.dto.*;
import com.mercala.identity.exception.ResourceConflictException;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.platform.multitenancy.TenantContext;

@Service
@Transactional
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final VariantRepository variantRepository;
    private final EmbeddingPort embeddingPort;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.search.rrf.k:60}")
    private int rrfK;

    @Value("${app.search.rrf.lexical-weight:1.0}")
    private double lexicalWeight;

    @Value("${app.search.rrf.semantic-weight:1.0}")
    private double semanticWeight;

    @Value("${app.search.rrf.candidate-limit:50}")
    private int candidateLimit;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            VariantRepository variantRepository,
            EmbeddingPort embeddingPort,
            ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.variantRepository = variantRepository;
        this.embeddingPort = embeddingPort;
        this.eventPublisher = eventPublisher;
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
        if (request.tags() != null) {
            product.setTags(request.tags());
        }

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
        eventPublisher.publishEvent(new ProductAdded(saved.getId(), tenantId));
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
    public Page<ProductResponse> getProducts(Pageable pageable) {
        UUID tenantId = getRequiredTenantId();
        return productRepository.findByTenantId(tenantId, pageable)
                .map(this::mapToProductResponse);
    }

    /** Storefront view: only ACTIVE products are visible to the public. */
    @Transactional(readOnly = true)
    public Page<ProductResponse> getActiveProducts(Pageable pageable) {
        UUID tenantId = getRequiredTenantId();
        return productRepository.findByTenantIdAndStatus(tenantId, ProductStatus.ACTIVE, pageable)
                .map(this::mapToProductResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> searchLexical(String query, Pageable pageable) {
        getRequiredTenantId(); // Enforce active tenant context validation
        return productRepository.searchLexical(query, pageable)
                .map(this::mapToProductResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> searchSemantic(String query, Pageable pageable) {
        getRequiredTenantId(); // Enforce active tenant context validation
        float[] queryEmbedding = embeddingPort.getEmbedding(query);
        
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < queryEmbedding.length; i++) {
            sb.append(queryEmbedding[i]);
            if (i < queryEmbedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        
        return productRepository.searchSemantic(sb.toString(), pageable)
                .map(this::mapToProductResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> searchHybrid(String query, Pageable pageable) {
        getRequiredTenantId(); // Enforce active tenant context validation

        if (query == null || query.trim().isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // Fetch top lexical candidates
        Page<Product> lexicalPage = productRepository.searchLexical(query, PageRequest.of(0, candidateLimit));
        List<Product> lexicalList = lexicalPage.getContent();

        // Fetch top semantic candidates
        float[] queryEmbedding = embeddingPort.getEmbedding(query);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < queryEmbedding.length; i++) {
            sb.append(queryEmbedding[i]);
            if (i < queryEmbedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");

        Page<Product> semanticPage = productRepository.searchSemantic(sb.toString(), PageRequest.of(0, candidateLimit));
        List<Product> semanticList = semanticPage.getContent();

        // Compute RRF scores
        Map<UUID, Product> productMap = new HashMap<>();
        Map<UUID, Double> lexicalRankMap = new HashMap<>();
        Map<UUID, Double> semanticRankMap = new HashMap<>();

        for (int i = 0; i < lexicalList.size(); i++) {
            Product p = lexicalList.get(i);
            productMap.put(p.getId(), p);
            lexicalRankMap.put(p.getId(), (double) (i + 1));
        }

        for (int i = 0; i < semanticList.size(); i++) {
            Product p = semanticList.get(i);
            productMap.put(p.getId(), p);
            semanticRankMap.put(p.getId(), (double) (i + 1));
        }

        Map<UUID, Double> rrfScores = new HashMap<>();
        for (UUID id : productMap.keySet()) {
            double lexicalScore = 0.0;
            if (lexicalRankMap.containsKey(id)) {
                lexicalScore = lexicalWeight / (rrfK + lexicalRankMap.get(id));
            }

            double semanticScore = 0.0;
            if (semanticRankMap.containsKey(id)) {
                semanticScore = semanticWeight / (rrfK + semanticRankMap.get(id));
            }

            rrfScores.put(id, lexicalScore + semanticScore);
        }

        // Sort by RRF score descending
        List<UUID> sortedIds = new ArrayList<>(rrfScores.keySet());
        sortedIds.sort((id1, id2) -> Double.compare(rrfScores.get(id2), rrfScores.get(id1)));

        // Paginate in-memory
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), sortedIds.size());

        List<ProductResponse> pageContent = new ArrayList<>();
        if (start < sortedIds.size()) {
            for (int i = start; i < end; i++) {
                Product product = productMap.get(sortedIds.get(i));
                pageContent.add(mapToProductResponse(product));
            }
        }

        return new PageImpl<>(pageContent, pageable, sortedIds.size());
    }

    public ProductResponse updateProduct(UUID productId, UpdateProductRequest request) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStatus(request.status());
        if (request.tags() != null) {
            product.setTags(request.tags());
        } else {
            product.setTags(new java.util.ArrayList<>());
        }

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
        eventPublisher.publishEvent(new ProductUpdated(saved.getId(), tenantId));
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

    public void updateEmbedding(UUID productId, float[] embedding) {
        UUID tenantId = getRequiredTenantId();
        Product product = productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        product.setEmbedding(embedding);
        productRepository.save(product);
        log.info("Successfully updated embedding via updateEmbedding endpoint for product: {}", productId);
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
                product.getTags(),
                varResponses,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
