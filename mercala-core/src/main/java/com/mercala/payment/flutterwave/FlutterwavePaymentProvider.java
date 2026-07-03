package com.mercala.payment.flutterwave;

import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.mercala.identity.AppUserRepository;
import com.mercala.order.OrderRepository;
import com.mercala.payment.PaymentProvider;
import com.mercala.payment.PaymentRequest;
import com.mercala.payment.PaymentResponse;
import com.mercala.payment.ProviderRefRepository;
import com.mercala.payment.RefundRequest;
import com.mercala.payment.RefundResponse;
import com.mercala.platform.multitenancy.TenantContext;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Component("flutterwavePaymentProvider")
public class FlutterwavePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(FlutterwavePaymentProvider.class);

    private final ProviderRefRepository providerRefRepository;
    private final OrderRepository orderRepository;
    private final AppUserRepository appUserRepository;
    private final RestTemplate restTemplate;
    private final String platformSecretKey;

    public FlutterwavePaymentProvider(
            ProviderRefRepository providerRefRepository,
            OrderRepository orderRepository,
            AppUserRepository appUserRepository,
            @Value("${mercala.payments.flutterwave.secret-key:mock_flutterwave_key}") String platformSecretKey) {
        this.providerRefRepository = providerRefRepository;
        this.orderRepository = orderRepository;
        this.appUserRepository = appUserRepository;
        this.restTemplate = new RestTemplate();
        this.platformSecretKey = platformSecretKey;
    }

    private String resolveSecretKey(UUID tenantId) {
        var providerRefOpt = providerRefRepository.findByTenantIdAndProviderAndEnabledTrue(tenantId, "FLUTTERWAVE");
        if (providerRefOpt.isPresent() && providerRefOpt.get().getSecretKey() != null && !providerRefOpt.get().getSecretKey().isBlank()) {
            return providerRefOpt.get().getSecretKey();
        }
        return platformSecretKey;
    }

    private boolean isMockMode(String secretKey) {
        return secretKey == null || secretKey.startsWith("mock_");
    }

    private String getShopperEmail(UUID orderId) {
        try {
            var orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isPresent()) {
                var userOpt = appUserRepository.findById(orderOpt.get().getUserId());
                if (userOpt.isPresent()) {
                    return userOpt.get().getEmail();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to lookup shopper email for order: {}. Using fallback.", orderId, e);
        }
        return "shopper@mercala.com";
    }

    @Override
    @CircuitBreaker(name = "flutterwave-payment", fallbackMethod = "fallbackInitialize")
    @Retry(name = "flutterwave-payment")
    public PaymentResponse initializePayment(PaymentRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Initializing Flutterwave payment for order: {}, amount: {}", request.orderId(), request.amount());
            String mockReference = "flw_" + UUID.randomUUID().toString().replace("-", "");
            String mockCheckoutUrl = "https://checkout.flutterwave.com/pay/" + mockReference;
            return PaymentResponse.pending(mockReference, mockCheckoutUrl);
        }

        String email = getShopperEmail(request.orderId());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(secretKey);

        java.util.Map<String, Object> customer = new java.util.HashMap<>();
        customer.put("email", email);
        customer.put("name", "Customer " + email.split("@")[0]);

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("tx_ref", request.idempotencyKey());
        body.put("amount", request.amount().doubleValue());
        body.put("currency", request.currency().toUpperCase());
        body.put("redirect_url", request.returnUrl());
        body.put("customer", customer);
        body.put("meta", java.util.Map.of("order_id", request.orderId().toString(), "tenant_id", tenantId.toString()));
        body.put("customizations", java.util.Map.of("title", "Mercala Shop Payment"));

        HttpEntity<java.util.Map<String, Object>> entity = new HttpEntity<>(body, headers);

        String url = "https://api.flutterwave.com/v3/payments";
        @SuppressWarnings("rawtypes")
        java.util.Map response = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                () -> restTemplate.postForObject(url, entity, java.util.Map.class),
                3, 100, 2.0
        );

        if (response != null && "success".equals(response.get("status"))) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.get("data");
            String checkoutUrl = (String) data.get("link");
            return PaymentResponse.pending(request.idempotencyKey(), checkoutUrl);
        } else {
            String errMsg = response != null ? (String) response.get("message") : "Empty response from Flutterwave";
            return PaymentResponse.failed(errMsg);
        }
    }

    @Override
    @CircuitBreaker(name = "flutterwave-payment", fallbackMethod = "fallbackVerify")
    @Retry(name = "flutterwave-payment")
    public PaymentResponse verifyPayment(String transactionReference) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Verifying Flutterwave transaction: {}", transactionReference);
            return PaymentResponse.successful(transactionReference, "{\"mock\": true, \"status\": \"successful\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = "https://api.flutterwave.com/v3/transactions/verify_by_reference?tx_ref=" + transactionReference;
        @SuppressWarnings("rawtypes")
        java.util.Map response = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                () -> restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, java.util.Map.class).getBody(),
                3, 100, 2.0
        );

        if (response != null && "success".equals(response.get("status"))) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.get("data");
            String status = (String) data.get("status");
            
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String rawJson = mapper.writeValueAsString(response);

                if ("successful".equals(status)) {
                    return PaymentResponse.successful(transactionReference, rawJson);
                } else {
                    return new PaymentResponse(
                            true,
                            "PENDING",
                            transactionReference,
                            null,
                            rawJson,
                            null
                    );
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize verify response", e);
            }
        } else {
            String errMsg = response != null ? (String) response.get("message") : "Empty verification response";
            return PaymentResponse.failed(errMsg);
        }
    }

    @Override
    @CircuitBreaker(name = "flutterwave-payment", fallbackMethod = "fallbackRefund")
    @Retry(name = "flutterwave-payment")
    public RefundResponse refundPayment(RefundRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Refunding Flutterwave transaction: {}, amount: {}", request.providerReference(), request.amount());
            return RefundResponse.successful("re_mock_flw_" + UUID.randomUUID());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(secretKey);

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("amount", request.amount().doubleValue());

        HttpEntity<java.util.Map<String, Object>> entity = new HttpEntity<>(body, headers);

        String url = "https://api.flutterwave.com/v3/transactions/" + request.providerReference() + "/refund";
        @SuppressWarnings("rawtypes")
        java.util.Map response = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                () -> restTemplate.postForObject(url, entity, java.util.Map.class),
                3, 100, 2.0
        );

        if (response != null && "success".equals(response.get("status"))) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.get("data");
            String refundReference = String.valueOf(data.get("id"));
            return RefundResponse.successful(refundReference);
        } else {
            String errMsg = response != null ? (String) response.get("message") : "Failed to refund transaction";
            return RefundResponse.failed(errMsg);
        }
    }

    /**
     * Fallbacks for Flutterwave payment provider operations.
     */
    public PaymentResponse fallbackInitialize(PaymentRequest request, Throwable t) {
        log.error("Flutterwave payment initialization failed or circuit is open. Fallback triggered. Error: {}", t.getMessage());
        return PaymentResponse.failed("Flutterwave service temporarily unavailable: " + t.getMessage());
    }

    public PaymentResponse fallbackVerify(String transactionReference, Throwable t) {
        log.error("Flutterwave payment verification failed or circuit is open. Fallback triggered. Error: {}", t.getMessage());
        return PaymentResponse.failed("Flutterwave verification service temporarily unavailable: " + t.getMessage());
    }

    public RefundResponse fallbackRefund(RefundRequest request, Throwable t) {
        log.error("Flutterwave refund failed or circuit is open. Fallback triggered. Error: {}", t.getMessage());
        return RefundResponse.failed("Flutterwave refund service temporarily unavailable: " + t.getMessage());
    }
}
