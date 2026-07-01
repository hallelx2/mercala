package com.mercala.order.web;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.order.Order;
import com.mercala.order.CheckoutService;
import com.mercala.order.web.dto.CheckoutRequest;
import com.mercala.order.web.dto.OrderLineResponse;
import com.mercala.order.web.dto.OrderResponse;
import com.mercala.platform.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/checkout")
@PreAuthorize("isAuthenticated()")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    public OrderResponse checkout(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @RequestBody(required = false) CheckoutRequest request) {

        String key = null;
        if (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank()) {
            key = headerIdempotencyKey;
        } else if (request != null) {
            key = request.idempotencyKey();
        }

        Order order = checkoutService.checkout(user.userId(), key);
        return mapToResponse(order);
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderLineResponse> lines = order.getLines().stream()
                .map(line -> new OrderLineResponse(
                        line.getId(),
                        line.getVariantId(),
                        line.getQuantity(),
                        line.getUnitPrice()
                ))
                .collect(Collectors.toList());

        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getIdempotencyKey(),
                lines
        );
    }
}
