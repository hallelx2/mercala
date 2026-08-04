package com.mercala.order.web;

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
import com.mercala.order.web.dto.OrderResponse;
import com.mercala.platform.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/checkout")
@PreAuthorize("isAuthenticated()")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final OrderAssembler assembler;

    public CheckoutController(CheckoutService checkoutService, OrderAssembler assembler) {
        this.checkoutService = checkoutService;
        this.assembler = assembler;
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

        Order order;
        try {
            order = checkoutService.checkout(user.userId(), key);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (key != null && !key.isBlank()) {
                order = checkoutService.findExistingOrder(key)
                        .orElseThrow(() -> e);
            } else {
                throw e;
            }
        }
        return mapToResponse(order);
    }

    private OrderResponse mapToResponse(Order order) {
        return assembler.assemble(order);
    }
}
