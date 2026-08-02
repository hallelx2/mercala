package com.mercala.order;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mercala.identity.Role;

/**
 * Read side for orders.
 *
 * <p>Checkout could create an order but nothing could read one back, so order state was
 * write-only over HTTP (HAL-477). That mattered more than it sounds: payment capture
 * happens asynchronously via webhook, so the {@code OrderResponse} returned by checkout is
 * a snapshot taken <em>before</em> settlement and is stale almost immediately.
 *
 * <p>Visibility is role-dependent, and deliberately enforced here rather than in the
 * controller. Tenant isolation is already guaranteed by the Hibernate filter and RLS, but
 * neither of those distinguishes one shopper from another <em>within</em> a tenant — a
 * shopper must not read another shopper's order even though both rows are legitimately
 * theirs at the tenant level.
 */
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Orders visible to the caller. Merchant roles see the whole tenant's orders; a shopper
     * sees only their own.
     */
    @Transactional(readOnly = true)
    public Page<Order> findVisible(UUID userId, Role role, OrderStatus status, Pageable pageable) {
        if (seesWholeTenant(role)) {
            return status == null
                    ? orderRepository.findAllBy(pageable)
                    : orderRepository.findByStatus(status, pageable);
        }
        return status == null
                ? orderRepository.findByUserId(userId, pageable)
                : orderRepository.findByUserIdAndStatus(userId, status, pageable);
    }

    /**
     * A single order, or empty when it does not exist <em>or</em> the caller may not see it.
     * Collapsing "absent" and "forbidden" into the same empty result is intentional — a 404
     * for someone else's order leaks nothing, whereas a 403 confirms the id exists.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Order> findVisibleById(UUID orderId, UUID userId, Role role) {
        return orderRepository.findById(orderId)
                .filter(order -> seesWholeTenant(role) || order.getUserId().equals(userId));
    }

    private static boolean seesWholeTenant(Role role) {
        return role == Role.MERCHANT_OWNER || role == Role.MERCHANT_STAFF || role == Role.PLATFORM_ADMIN;
    }
}
