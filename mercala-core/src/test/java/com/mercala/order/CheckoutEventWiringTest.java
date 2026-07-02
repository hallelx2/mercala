package com.mercala.order;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.mercala.AbstractIntegrationTest;
import com.mercala.cart.CartService;
import com.mercala.catalog.Category;
import com.mercala.catalog.CategoryRepository;
import com.mercala.catalog.Product;
import com.mercala.catalog.ProductRepository;
import com.mercala.catalog.Variant;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.inventory.InventoryService;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutEventWiringTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CartService cartService;
    @Autowired private InventoryService inventoryService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private Variant createTestVariantWithStock(Tenant tenant, int stock) {
        Category category = categoryRepository.save(new Category(tenant.getId(), "Test Cat", "test-cat-" + UUID.randomUUID()));
        Product product = new Product(tenant.getId(), "Test Product", "Desc", BigDecimal.TEN, category);
        Variant variant = new Variant("SKU-" + UUID.randomUUID(), java.util.Map.of("color", "blue"), BigDecimal.TEN, "ref");
        product.addVariant(variant);
        productRepository.save(product);
        inventoryService.adjustStock(variant.getId(), stock);
        return variant;
    }

    @Test
    void reactionsFireOnlyAfterCommit() {
        Tenant tenant = tenantRepository.save(new Tenant("event-tenant-1", "Event Tenant 1"));
        UUID userId = UUID.randomUUID();

        TenantContext.setCurrentTenant(tenant.getId());
        final UUID variantId;
        try {
            Variant variant = createTestVariantWithStock(tenant, 10);
            variantId = variant.getId();
            cartService.addOrUpdateLine(userId, variantId, 2);
        } finally {
            TenantContext.clear();
        }

        // Execute checkout in a committed transaction
        Order order = transactionTemplate.execute(status -> {
            TenantContext.setCurrentTenant(tenant.getId());
            try {
                return checkoutService.checkout(userId, "key-commit");
            } finally {
                TenantContext.clear();
            }
        });

        // After commit, verify stock is reserved and cart is cleared
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            var stockItem = inventoryService.getStockItem(variantId);
            assertThat(stockItem.getReservedQuantity()).isEqualTo(2);

            var cart = cartService.getOrCreateCart(userId);
            assertThat(cart.getLines()).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void reactionsDoNotFireOnRollback() {
        Tenant tenant = tenantRepository.save(new Tenant("event-tenant-2", "Event Tenant 2"));
        UUID userId = UUID.randomUUID();

        TenantContext.setCurrentTenant(tenant.getId());
        final UUID variantId;
        try {
            Variant variant = createTestVariantWithStock(tenant, 10);
            variantId = variant.getId();
            cartService.addOrUpdateLine(userId, variantId, 3);
        } finally {
            TenantContext.clear();
        }

        // Execute checkout in a transaction that gets rolled back
        transactionTemplate.execute(status -> {
            TenantContext.setCurrentTenant(tenant.getId());
            try {
                checkoutService.checkout(userId, "key-rollback");
                status.setRollbackOnly(); // Force rollback
                return null;
            } finally {
                TenantContext.clear();
            }
        });

        // After rollback, verify stock is NOT reserved and cart is NOT cleared
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            var stockItem = inventoryService.getStockItem(variantId);
            assertThat(stockItem.getReservedQuantity()).isEqualTo(0);

            var cart = cartService.getOrCreateCart(userId);
            assertThat(cart.getLines()).hasSize(1);
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(3);
        } finally {
            TenantContext.clear();
        }
    }
}
