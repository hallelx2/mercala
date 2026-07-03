package com.mercala.payment.stripe;

import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mercala.payment.PaymentProvider;
import com.mercala.payment.PaymentRequest;
import com.mercala.payment.PaymentResponse;
import com.mercala.payment.ProviderRefRepository;
import com.mercala.payment.RefundRequest;
import com.mercala.payment.RefundResponse;
import com.mercala.platform.multitenancy.TenantContext;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Component("stripePaymentProvider")
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);

    private final ProviderRefRepository providerRefRepository;
    private final String platformSecretKey;

    public StripePaymentProvider(
            ProviderRefRepository providerRefRepository,
            @Value("${mercala.payments.stripe.secret-key:mock_stripe_key}") String platformSecretKey) {
        this.providerRefRepository = providerRefRepository;
        this.platformSecretKey = platformSecretKey;
    }

    private String resolveSecretKey(UUID tenantId) {
        var providerRefOpt = providerRefRepository.findByTenantIdAndProviderAndEnabledTrue(tenantId, "STRIPE");
        if (providerRefOpt.isPresent() && providerRefOpt.get().getSecretKey() != null && !providerRefOpt.get().getSecretKey().isBlank()) {
            return providerRefOpt.get().getSecretKey();
        }
        return platformSecretKey;
    }

    private RequestOptions getRequestOptions(UUID tenantId, String secretKey) {
        var providerRefOpt = providerRefRepository.findByTenantIdAndProviderAndEnabledTrue(tenantId, "STRIPE");
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder().setApiKey(secretKey);

        if (providerRefOpt.isPresent() && providerRefOpt.get().getConnectedAccountId() != null && !providerRefOpt.get().getConnectedAccountId().isBlank()) {
            String connectedAccountId = providerRefOpt.get().getConnectedAccountId();
            log.info("Using Stripe Connect direct charge on account: {}", connectedAccountId);
            builder.setStripeAccount(connectedAccountId);
        }

        return builder.build();
    }

    private boolean isMockMode(String secretKey) {
        return secretKey == null || secretKey.startsWith("mock_");
    }

    @Override
    @CircuitBreaker(name = "stripe-payment", fallbackMethod = "fallbackInitialize")
    @Retry(name = "stripe-payment")
    public PaymentResponse initializePayment(PaymentRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);
        RequestOptions options = getRequestOptions(tenantId, secretKey).toBuilder()
                .setIdempotencyKey(request.idempotencyKey())
                .build();

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Initializing Stripe Checkout session for order: {}, amount: {}", request.orderId(), request.amount());
            String mockSessionId = "cs_test_" + UUID.randomUUID();
            String mockCheckoutUrl = "https://checkout.stripe.com/c/pay/" + mockSessionId;
            return PaymentResponse.pending(mockSessionId, mockCheckoutUrl);
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(request.returnUrl() + "?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(request.returnUrl() + "?status=cancelled")
                .addLineItem(
                    SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(
                            SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(request.currency().toLowerCase())
                                .setUnitAmount(request.amount().multiply(BigDecimal.valueOf(100)).longValue())
                                .setProductData(
                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Order Ref: " + request.orderId())
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .putMetadata("order_id", request.orderId().toString())
                .putMetadata("tenant_id", tenantId.toString())
                .build();

        Session session = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                () -> {
                    try {
                        return Session.create(params, options);
                    } catch (com.stripe.exception.StripeException e) {
                        throw new RuntimeException(e);
                    }
                },
                3, 100, 2.0
        );
        return PaymentResponse.pending(session.getId(), session.getUrl());
    }

    @Override
    @CircuitBreaker(name = "stripe-payment", fallbackMethod = "fallbackVerify")
    @Retry(name = "stripe-payment")
    public PaymentResponse verifyPayment(String transactionReference) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);
        RequestOptions options = getRequestOptions(tenantId, secretKey);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Verifying Stripe Checkout session: {}", transactionReference);
            return PaymentResponse.successful(transactionReference, "{\"mock\": true, \"status\": \"paid\"}");
        }

        Session session = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                () -> {
                    try {
                        return Session.retrieve(transactionReference, options);
                    } catch (com.stripe.exception.StripeException e) {
                        throw new RuntimeException(e);
                    }
                },
                3, 100, 2.0
        );
        if ("paid".equals(session.getPaymentStatus())) {
            return PaymentResponse.successful(session.getId(), session.toJson());
        } else {
            return new PaymentResponse(
                    true,
                    "PENDING",
                    session.getId(),
                    session.getUrl(),
                    session.toJson(),
                    null
            );
        }
    }

    @Override
    @CircuitBreaker(name = "stripe-payment", fallbackMethod = "fallbackRefund")
    @Retry(name = "stripe-payment")
    public RefundResponse refundPayment(RefundRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);
        RequestOptions options = getRequestOptions(tenantId, secretKey).toBuilder()
                .setIdempotencyKey(request.paymentId().toString())
                .build();

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Refunding Stripe payment reference: {}, amount: {}", request.providerReference(), request.amount());
            return RefundResponse.successful("re_mock_" + UUID.randomUUID());
        }

        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(request.providerReference())
                .setAmount(request.amount().multiply(BigDecimal.valueOf(100)).longValue())
                .build();

        com.stripe.model.Refund refund = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                () -> {
                    try {
                        return com.stripe.model.Refund.create(params, options);
                    } catch (com.stripe.exception.StripeException e) {
                        throw new RuntimeException(e);
                    }
                },
                3, 100, 2.0
        );
        return RefundResponse.successful(refund.getId());
    }

    /**
     * Fallbacks for Stripe payment provider operations.
     */
    public PaymentResponse fallbackInitialize(PaymentRequest request, Throwable t) {
        log.error("Stripe payment initialization failed or circuit is open. Fallback triggered. Error: {}", t.getMessage());
        return PaymentResponse.failed("Stripe service temporarily unavailable: " + t.getMessage());
    }

    public PaymentResponse fallbackVerify(String transactionReference, Throwable t) {
        log.error("Stripe payment verification failed or circuit is open. Fallback triggered. Error: {}", t.getMessage());
        return PaymentResponse.failed("Stripe verification service temporarily unavailable: " + t.getMessage());
    }

    public RefundResponse fallbackRefund(RefundRequest request, Throwable t) {
        log.error("Stripe refund failed or circuit is open. Fallback triggered. Error: {}", t.getMessage());
        return RefundResponse.failed("Stripe refund service temporarily unavailable: " + t.getMessage());
    }
}
