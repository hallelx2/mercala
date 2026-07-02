package com.mercala.order;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.cart.Cart;
import com.mercala.cart.CartLine;
import com.mercala.cart.CartService;
import com.mercala.catalog.Variant;
import com.mercala.catalog.VariantRepository;
import com.mercala.identity.exception.ResourceNotFoundException;
import com.mercala.inventory.InventoryService;
import com.mercala.inventory.StockItem;
import com.mercala.platform.multitenancy.TenantContext;

@Service
@Transactional
public class CheckoutService {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final InventoryService inventoryService;
    private final VariantRepository variantRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public CheckoutService(
            OrderRepository orderRepository,
            CartService cartService,
            InventoryService inventoryService,
            VariantRepository variantRepository,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
        this.inventoryService = inventoryService;
        this.variantRepository = variantRepository;
        this.eventPublisher = eventPublisher;
    }

    public Order checkout(UUID userId, String idempotencyKey) {
        UUID tenantId = getRequiredTenantId();

        // 1. Check idempotency
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingOrder = orderRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
        }

        // 2. Load shopper cart
        Cart cart = cartService.getOrCreateCart(userId);
        if (cart.getLines().isEmpty()) {
            throw new IllegalStateException("Cannot checkout an empty cart");
        }

        // 3. Pre-load all variants into a map to avoid redundant database lookups
        java.util.Map<UUID, Variant> variantMap = new java.util.HashMap<>();
        for (CartLine line : cart.getLines()) {
            Variant variant = variantRepository.findByIdAndTenantId(line.getVariantId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Variant not found: " + line.getVariantId()));
            variantMap.put(line.getVariantId(), variant);
        }

        // 4. Validate stock & calculate price
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartLine line : cart.getLines()) {
            Variant variant = variantMap.get(line.getVariantId());

            // Validate inventory availability
            StockItem stockItem = inventoryService.getStockItem(line.getVariantId());
            if (stockItem.getAvailableQuantity() < line.getQuantity()) {
                throw new IllegalStateException("Insufficient stock for variant SKU " + variant.getSku() 
                        + ". Requested: " + line.getQuantity() + ", Available: " + stockItem.getAvailableQuantity());
            }

            BigDecimal lineTotal = variant.getPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
            totalAmount = totalAmount.add(lineTotal);
        }

        // 5. Create Order
        Order order = new Order(tenantId, userId, totalAmount, idempotencyKey);
        for (CartLine line : cart.getLines()) {
            Variant variant = variantMap.get(line.getVariantId());
            order.addLine(line.getVariantId(), line.getQuantity(), variant.getPrice());
        }

        Order savedOrder = orderRepository.save(order);

        // 6. Publish event for post-commit processing (stock reservation, cart clearing)
        eventPublisher.publishEvent(new com.mercala.order.event.OrderPlacedEvent(savedOrder.getId(), userId, tenantId));

        return savedOrder;
    }

    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public java.util.Optional<Order> findExistingOrder(String idempotencyKey) {
        UUID tenantId = getRequiredTenantId();
        return orderRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
    }

    private UUID getRequiredTenantId() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Tenant context is required");
        }
        return tenantId;
    }
}
