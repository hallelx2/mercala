package com.mercala.order.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mercala.cart.CartService;
import com.mercala.inventory.InventoryService;
import com.mercala.order.Order;
import com.mercala.order.OrderRepository;
import com.mercala.order.OrderStatus;
import com.mercala.order.event.OrderPlacedEvent;
import com.mercala.platform.multitenancy.TenantContext;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final InventoryService inventoryService;
    private final CartService cartService;
    private final OrderRepository orderRepository;

    public OrderEventListener(
            InventoryService inventoryService,
            CartService cartService,
            OrderRepository orderRepository) {
        this.inventoryService = inventoryService;
        this.cartService = cartService;
        this.orderRepository = orderRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOrderPlaced(OrderPlacedEvent event) {
        log.info("Handling OrderPlacedEvent for order: {} in tenant: {}", event.orderId(), event.tenantId());

        TenantContext.setCurrentTenant(event.tenantId());
        try {
            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new IllegalStateException("Order not found: " + event.orderId()));

            // 1. Reserve stock for each variant in the order
            try {
                for (var line : order.getLines()) {
                    inventoryService.reserveStock(line.getVariantId(), line.getQuantity());
                }
            } catch (Exception e) {
                log.error("Stock reservation failed for order: {}. Cancelling order.", event.orderId(), e);
                order.transitionTo(OrderStatus.CANCELLED);
                orderRepository.save(order);
                return;
            }

            // 2. Clear shopper cart
            cartService.clearCart(event.userId());

            // 3. Initiate payment simulation / log payment hook trigger
            log.info("Successfully processed order placement reactions for order: {}", event.orderId());
        } finally {
            TenantContext.clear();
        }
    }
}
