package com.mercala.payment.paystack;

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

@Component("paystackPaymentProvider")
public class PaystackPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PaystackPaymentProvider.class);

    private final ProviderRefRepository providerRefRepository;
    private final OrderRepository orderRepository;
    private final AppUserRepository appUserRepository;
    private final RestTemplate restTemplate;
    private final String platformSecretKey;

    public PaystackPaymentProvider(
            ProviderRefRepository providerRefRepository,
            OrderRepository orderRepository,
            AppUserRepository appUserRepository,
            @Value("${mercala.payments.paystack.secret-key:mock_paystack_key}") String platformSecretKey) {
        this.providerRefRepository = providerRefRepository;
        this.orderRepository = orderRepository;
        this.appUserRepository = appUserRepository;
        this.restTemplate = new RestTemplate();
        this.platformSecretKey = platformSecretKey;
    }

    private String resolveSecretKey(UUID tenantId) {
        var providerRefOpt = providerRefRepository.findByTenantIdAndProviderAndEnabledTrue(tenantId, "PAYSTACK");
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
    public PaymentResponse initializePayment(PaymentRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Initializing Paystack payment for order: {}, amount: {}", request.orderId(), request.amount());
            String mockReference = "pstk_" + UUID.randomUUID().toString().replace("-", "");
            String mockCheckoutUrl = "https://checkout.paystack.com/" + mockReference;
            return PaymentResponse.pending(mockReference, mockCheckoutUrl);
        }

        try {
            String email = getShopperEmail(request.orderId());
            long amountInKobo = request.amount().multiply(BigDecimal.valueOf(100)).longValue();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(secretKey);

            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("email", email);
            body.put("amount", amountInKobo);
            body.put("reference", request.idempotencyKey());
            body.put("callback_url", request.returnUrl());
            body.put("metadata", java.util.Map.of("order_id", request.orderId().toString(), "tenant_id", tenantId.toString()));

            HttpEntity<java.util.Map<String, Object>> entity = new HttpEntity<>(body, headers);

            String url = "https://api.paystack.co/transaction/initialize";
            @SuppressWarnings("rawtypes")
            java.util.Map response = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                    () -> restTemplate.postForObject(url, entity, java.util.Map.class),
                    3, 100, 2.0
            );

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.get("data");
                String checkoutUrl = (String) data.get("authorization_url");
                String reference = (String) data.get("reference");
                return PaymentResponse.pending(reference, checkoutUrl);
            } else {
                String errMsg = response != null ? (String) response.get("message") : "Empty response from Paystack";
                return PaymentResponse.failed(errMsg);
            }
        } catch (Exception e) {
            log.error("Paystack transaction initialization failed for order: {}", request.orderId(), e);
            return PaymentResponse.failed(e.getMessage());
        }
    }

    @Override
    public PaymentResponse verifyPayment(String transactionReference) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Verifying Paystack transaction: {}", transactionReference);
            return PaymentResponse.successful(transactionReference, "{\"mock\": true, \"status\": \"success\"}");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(secretKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = "https://api.paystack.co/transaction/verify/" + transactionReference;
            @SuppressWarnings("rawtypes")
            java.util.Map response = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                    () -> restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, java.util.Map.class).getBody(),
                    3, 100, 2.0
            );

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.get("data");
                String status = (String) data.get("status");
                
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String rawJson = mapper.writeValueAsString(response);

                if ("success".equals(status)) {
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
            } else {
                String errMsg = response != null ? (String) response.get("message") : "Empty verification response";
                return PaymentResponse.failed(errMsg);
            }
        } catch (Exception e) {
            log.error("Paystack transaction verification failed for reference: {}", transactionReference, e);
            return PaymentResponse.failed(e.getMessage());
        }
    }

    @Override
    public RefundResponse refundPayment(RefundRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String secretKey = resolveSecretKey(tenantId);

        if (isMockMode(secretKey)) {
            log.info("[MOCK] Refunding Paystack transaction: {}, amount: {}", request.providerReference(), request.amount());
            return RefundResponse.successful("re_mock_ps_" + UUID.randomUUID());
        }

        try {
            long amountInKobo = request.amount().multiply(BigDecimal.valueOf(100)).longValue();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(secretKey);

            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("transaction", request.providerReference());
            body.put("amount", amountInKobo);

            HttpEntity<java.util.Map<String, Object>> entity = new HttpEntity<>(body, headers);

            String url = "https://api.paystack.co/refund";
            @SuppressWarnings("rawtypes")
            java.util.Map response = com.mercala.payment.resilience.PaymentRetryTemplate.execute(
                    () -> restTemplate.postForObject(url, entity, java.util.Map.class),
                    3, 100, 2.0
            );

            if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> data = (java.util.Map<String, Object>) response.get("data");
                String refundReference = String.valueOf(data.get("id"));
                return RefundResponse.successful(refundReference);
            } else {
                String errMsg = response != null ? (String) response.get("message") : "Failed to refund transaction";
                return RefundResponse.failed(errMsg);
            }
        } catch (Exception e) {
            log.error("Paystack refund failed for reference: {}", request.providerReference(), e);
            return RefundResponse.failed(e.getMessage());
        }
    }
}
