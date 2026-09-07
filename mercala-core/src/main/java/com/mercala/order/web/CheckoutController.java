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
import com.mercala.order.web.dto.CheckoutResponse;
import com.mercala.order.web.dto.OrderResponse;
import com.mercala.payment.PaymentInitiationService;
import com.mercala.platform.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/checkout")
@PreAuthorize("isAuthenticated()")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final OrderAssembler assembler;
    private final PaymentInitiationService paymentInitiationService;

    public CheckoutController(
            CheckoutService checkoutService,
            OrderAssembler assembler,
            PaymentInitiationService paymentInitiationService) {
        this.checkoutService = checkoutService;
        this.assembler = assembler;
        this.paymentInitiationService = paymentInitiationService;
    }

    @PostMapping
    public CheckoutResponse checkout(
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
        // Order is committed and stock is reserved before we talk to a provider. The HTTP call
        // out to Stripe or Paystack must not sit inside the checkout transaction.
        var initiation = paymentInitiationService.initiateForOrder(
                order.getTenantId(),
                order.getId(),
                order.getTotalAmount(),
                key,
                request == null ? null : request.preferredProvider());

        return new CheckoutResponse(mapToResponse(order), toPaymentInfo(initiation));
    }

    private CheckoutResponse.PaymentInfo toPaymentInfo(PaymentInitiationService.Initiation initiation) {
        if (initiation == null) {
            return null;
        }
        var r = initiation.response();
        return new CheckoutResponse.PaymentInfo(
                initiation.provider(), r.status(), r.checkoutUrl(), r.providerReference());
    }

    private OrderResponse mapToResponse(Order order) {
        return assembler.assemble(order);
    }
}
