package com.mercala.order.web;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mercala.order.Order;
import com.mercala.order.OrderQueryService;
import com.mercala.order.OrderStatus;
import com.mercala.order.web.dto.OrderLineResponse;
import com.mercala.order.web.dto.OrderResponse;
import com.mercala.platform.security.AuthenticatedUser;

/**
 * Read access to orders.
 *
 * <p>Checkout created orders that nothing could read back (HAL-477). That blocked order
 * confirmation on reload, shopper order history, and merchant order management — and it
 * mattered even for the session that placed the order, because payment settles
 * asynchronously via webhook, so the checkout response goes stale the moment it is
 * returned.
 *
 * <p>Who sees what is decided in {@link OrderQueryService}, not here. Tenant isolation is
 * already handled by the Hibernate filter and RLS; the service adds the within-tenant rule
 * that a shopper sees only their own orders.
 */
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderQueryService orderQueryService;

    public OrderController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    /**
     * Newest first by default — an order list is read chronologically, and defaulting to
     * insertion order would make the most relevant row the hardest to find.
     */
    @GetMapping
    public Page<OrderResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        return orderQueryService
                .findVisible(user.userId(), user.role(), status, pageable)
                .map(OrderController::mapToResponse);
    }

    /**
     * Returns 404 rather than 403 for an order belonging to another shopper. A 403 would
     * confirm the id exists, which is a small but free information leak on a guessable
     * resource.
     */
    @GetMapping("/{id}")
    public OrderResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id) {

        return orderQueryService.findVisibleById(id, user.userId(), user.role())
                .map(OrderController::mapToResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private static OrderResponse mapToResponse(Order order) {
        List<OrderLineResponse> lines = order.getLines().stream()
                .map(line -> new OrderLineResponse(
                        line.getId(),
                        line.getVariantId(),
                        line.getQuantity(),
                        line.getUnitPrice()))
                .collect(Collectors.toList());

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getIdempotencyKey(),
                lines,
                order.getCreatedAt());
    }
}
