package com.mercala.inventory;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.mercala.AbstractIntegrationTest;
import com.mercala.catalog.Category;
import com.mercala.catalog.CategoryRepository;
import com.mercala.catalog.Product;
import com.mercala.catalog.ProductRepository;
import com.mercala.catalog.Variant;
import com.mercala.catalog.VariantRepository;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryRepositoryTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private StockItemRepository stockItemRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private VariantRepository variantRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Variant createTestVariant(Tenant tenant) {
        Category category = categoryRepository.save(new Category(tenant.getId(), "Test Category", "test-category-" + UUID.randomUUID()));
        Product product = new Product(tenant.getId(), "Test Product", "Description", new java.math.BigDecimal("99.99"), category);
        Variant variant = new Variant("TEST-SKU-" + UUID.randomUUID(), java.util.Map.of("color", "Red"), new java.math.BigDecimal("99.99"), "ref-1");
        product.addVariant(variant);
        productRepository.save(product);
        return variant;
    }

    @Test
    void savesAndRetrievesStockItem() {
        Tenant tenant = tenantRepository.save(new Tenant("inventory-store-1", "Inventory Store 1"));
        TenantContext.setCurrentTenant(tenant.getId());

        try {
            Variant variant = createTestVariant(tenant);
            StockItem item = new StockItem(tenant.getId(), variant.getId(), 100);
            StockItem saved = stockItemRepository.save(item);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getTenantId()).isEqualTo(tenant.getId());
            assertThat(saved.getVariantId()).isEqualTo(variant.getId());
            assertThat(saved.getQuantity()).isEqualTo(100);
            assertThat(saved.getReservedQuantity()).isEqualTo(0);
            assertThat(saved.getAvailableQuantity()).isEqualTo(100);
            assertThat(saved.getVersion()).isEqualTo(0L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void enforcesTenantIsolation() {
        Tenant tenantA = tenantRepository.save(new Tenant("tenant-a-inv", "Tenant A Inv"));
        Tenant tenantB = tenantRepository.save(new Tenant("tenant-b-inv", "Tenant B Inv"));

        final UUID variantAId;
        final UUID variantBId;

        // Save under Tenant A context
        TenantContext.setCurrentTenant(tenantA.getId());
        try {
            Variant variantA = createTestVariant(tenantA);
            variantAId = variantA.getId();
            stockItemRepository.save(new StockItem(tenantA.getId(), variantAId, 50));
        } finally {
            TenantContext.clear();
        }

        // Save under Tenant B context
        TenantContext.setCurrentTenant(tenantB.getId());
        try {
            Variant variantB = createTestVariant(tenantB);
            variantBId = variantB.getId();
            stockItemRepository.save(new StockItem(tenantB.getId(), variantBId, 80));
        } finally {
            TenantContext.clear();
        }

        // Query under Tenant A context -> Should only see Tenant A's StockItem
        transactionTemplate.execute(status -> {
            TenantContext.setCurrentTenant(tenantA.getId());
            try {
                var items = stockItemRepository.findAll();
                assertThat(items).hasSize(1);
                assertThat(items.get(0).getQuantity()).isEqualTo(50);
                assertThat(stockItemRepository.findByVariantId(variantBId)).isEmpty();
                assertThat(stockItemRepository.findByVariantId(variantAId)).isPresent();
            } finally {
                TenantContext.clear();
            }
            return null;
        });

        // Query under Tenant B context -> Should only see Tenant B's StockItem
        transactionTemplate.execute(status -> {
            TenantContext.setCurrentTenant(tenantB.getId());
            try {
                var items = stockItemRepository.findAll();
                assertThat(items).hasSize(1);
                assertThat(items.get(0).getQuantity()).isEqualTo(80);
                assertThat(stockItemRepository.findByVariantId(variantAId)).isEmpty();
                assertThat(stockItemRepository.findByVariantId(variantBId)).isPresent();
            } finally {
                TenantContext.clear();
            }
            return null;
        });
    }

    @Test
    void enforcesOptimisticLockingOnConcurrentUpdates() throws InterruptedException {
        Tenant tenant = tenantRepository.save(new Tenant("optimistic-store", "Optimistic Store"));

        // 1. Create a stock item
        TenantContext.setCurrentTenant(tenant.getId());
        final UUID stockItemId;
        try {
            Variant variant = createTestVariant(tenant);
            StockItem saved = stockItemRepository.save(new StockItem(tenant.getId(), variant.getId(), 10));
            stockItemId = saved.getId();
        } finally {
            TenantContext.clear();
        }

        // 2. Perform concurrent updates
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Runnable updateTask = () -> {
            try {
                // Wait for the latch signal to run concurrently
                latch.await();
                
                transactionTemplate.execute(status -> {
                    TenantContext.setCurrentTenant(tenant.getId());
                    try {
                        StockItem item = stockItemRepository.findById(stockItemId).orElseThrow();
                        item.reserve(1); // reserve 1 unit
                        stockItemRepository.saveAndFlush(item);
                        successCount.incrementAndGet();
                    } finally {
                        TenantContext.clear();
                    }
                    return null;
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                failureCount.incrementAndGet();
            } catch (Exception e) {
                // other unexpected exceptions
            }
        };

        executor.submit(updateTask);
        executor.submit(updateTask);

        // Start threads
        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        // One update must succeed, the other must fail due to optimistic locking (version mismatch)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
    }
}
