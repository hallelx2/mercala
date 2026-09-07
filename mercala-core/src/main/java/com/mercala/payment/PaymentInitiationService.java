package com.mercala.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mercala.platform.multitenancy.TenantContext;

/**
 * Starts a payment for an order that has already been placed.
 *
 * <p>This is the call that was missing. The providers, the routing and the inbound webhook
 * all existed, but nothing ever asked a {@link PaymentProvider} to charge anybody, so
 * checkout reached {@code PLACED}, reserved stock, and never took money.
 *
 * <p>Deliberately separate from {@code CheckoutService}: that class is {@code @Transactional}
 * at the class level, and an outbound HTTP call to Stripe or Paystack must not run inside the
 * transaction that is holding the order and stock rows. The controller places the order,
 * commits, and then calls this.
 */
@Service
public class PaymentInitiationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiationService.class);

    private final PaymentRoutingService routingService;
    private final PaymentRepository paymentRepository;
    private final String defaultCurrency;
    private final String returnUrl;

    public PaymentInitiationService(
            PaymentRoutingService routingService,
            PaymentRepository paymentRepository,
            @Value("${mercala.payment.default-currency:GBP}") String defaultCurrency,
            @Value("${mercala.payment.return-url:}") String returnUrl) {
        this.routingService = routingService;
        this.paymentRepository = paymentRepository;
        this.defaultCurrency = defaultCurrency;
        this.returnUrl = returnUrl;
    }

    /**
     * The outcome of an initiation attempt: which provider handled it, and what it said.
     *
     * @param provider canonical provider name, e.g. PAYSTACK. Null when routing itself failed.
     */
    public record Initiation(String provider, PaymentResponse response) {}

    /**
     * Initiate payment for an order, or return the existing attempt.
     *
     * <p>Idempotent on {@code orderId}: a second call for the same order does not charge
     * twice, it returns what the first attempt produced. That matters because the checkout
     * endpoint is itself idempotent, and a retried checkout must not create a second charge.
     *
     * @return the provider used and its response, or a failure if routing or the provider threw.
     *         Never propagates: a placed order must not be lost because a provider was down.
     */
    public Initiation initiateForOrder(UUID tenantId, UUID orderId, BigDecimal amount,
                                       String idempotencyKey, String preferredProvider) {
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required to initiate payment");
        }

        // The tenant is passed in rather than read off TenantContext, and is re-established
        // here for the duration of the call. OrderPlacedEvent is handled AFTER_COMMIT on this
        // same thread and clears the ThreadLocal in its finally block, so by the time the
        // controller gets here the request's tenant is already gone. Routing still reads the
        // ThreadLocal, so it has to be put back — and put back exactly as it was found.
        UUID previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenantId);
        try {
            return doInitiate(tenantId, orderId, amount, idempotencyKey, preferredProvider);
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private Initiation doInitiate(UUID tenantId, UUID orderId, BigDecimal amount,
                                  String idempotencyKey, String preferredProvider) {
        var existing = paymentRepository.findByOrderIdAndTenantId(orderId, tenantId);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            log.info("Payment already initiated for order {} ({}), not charging again", orderId, payment.getStatus());
            return new Initiation(payment.getProvider(), new PaymentResponse(
                    payment.getStatus() != PaymentStatus.FAILED,
                    payment.getStatus().name(),
                    payment.getProviderReference(),
                    null,
                    null,
                    null));
        }

        PaymentRoutingService.RoutedProvider routed;
        try {
            routed = routingService.route(preferredProvider);
        } catch (RuntimeException e) {
            log.error("Could not route a payment provider for order {}: {}", orderId, e.getMessage());
            return new Initiation(null, PaymentResponse.failed("No payment provider available: " + e.getMessage()));
        }

        PaymentRequest request = new PaymentRequest(
                orderId, amount, defaultCurrency, idempotencyKey, returnUrl.isBlank() ? null : returnUrl);

        PaymentResponse response;
        try {
            response = routed.provider().initializePayment(request);
        } catch (RuntimeException e) {
            log.error("Provider {} failed to initialize payment for order {}", routed.name(), orderId, e);
            record(tenantId, orderId, amount, routed.name(), idempotencyKey, null, PaymentStatus.FAILED);
            return new Initiation(routed.name(), PaymentResponse.failed(e.getMessage()));
        }

        PaymentStatus status = response.success() ? PaymentStatus.PENDING : PaymentStatus.FAILED;
        record(tenantId, orderId, amount, routed.name(), idempotencyKey, response.providerReference(), status);

        log.info("Initiated {} payment for order {} via {} (reference: {})",
                status, orderId, routed.name(), response.providerReference());
        return new Initiation(routed.name(), response);
    }

    /**
     * Persist the attempt.
     *
     * <p>No {@code @Transactional} here on purpose. This is called from
     * {@link #initiateForOrder}, and a self-invocation does not pass through the Spring proxy,
     * so the annotation would be silently inert. {@code save} carries its own transaction,
     * which is what actually commits the row, and recording it matters: a charge we started
     * and did not record leaves the webhook with nothing to match against.
     */
    void record(UUID tenantId, UUID orderId, BigDecimal amount, String provider,
                String idempotencyKey, String providerReference, PaymentStatus status) {
        Payment payment = new Payment(tenantId, orderId, amount, defaultCurrency, provider, idempotencyKey);
        payment.setStatus(status);
        if (providerReference != null) {
            payment.setProviderReference(providerReference);
        }
        paymentRepository.save(payment);
    }

}
