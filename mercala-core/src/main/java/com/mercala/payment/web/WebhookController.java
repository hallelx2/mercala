package com.mercala.payment.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercala.payment.Payment;
import com.mercala.payment.PaymentRepository;
import com.mercala.payment.PaymentStatus;
import com.mercala.payment.ProviderRefRepository;
import com.mercala.payment.event.PaymentCapturedEvent;
import com.mercala.platform.multitenancy.TenantContext;
import com.stripe.model.Event;

// Every endpoint here is public by necessity — payment providers call them with no
// Mercala token. Authenticity comes from per-provider signature verification, not from
// the Authorization header, so the document must not claim a bearer token is required.
@SecurityRequirements
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentRepository paymentRepository;
    private final ProviderRefRepository providerRefRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${mercala.payments.stripe.webhook-secret:mock_stripe_webhook_secret}")
    private String stripeWebhookSecret;

    @Value("${mercala.payments.paystack.secret-key:mock_paystack_key}")
    private String paystackSecretKey;

    @Value("${mercala.payments.flutterwave.webhook-secret:mock_flutterwave_webhook_secret}")
    private String flutterwaveWebhookSecret;

    public WebhookController(
            PaymentRepository paymentRepository,
            ProviderRefRepository providerRefRepository,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.providerRefRepository = providerRefRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/stripe")
    @Transactional
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        log.info("Received Stripe webhook");

        try {
            if (stripeWebhookSecret.startsWith("mock_")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> eventMap = objectMapper.readValue(payload, Map.class);
                String eventType = (String) eventMap.get("type");
                
                if ("checkout.session.completed".equals(eventType) || "payment_intent.succeeded".equals(eventType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) eventMap.get("data");
                    if (data != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dataObject = (Map<String, Object>) data.get("object");
                        if (dataObject != null) {
                            String sessionId = (String) dataObject.get("id");
                            @SuppressWarnings("unchecked")
                            Map<String, String> metadata = (Map<String, String>) dataObject.get("metadata");

                            if (metadata != null && metadata.containsKey("order_id") && metadata.containsKey("tenant_id")) {
                                UUID orderId = UUID.fromString(metadata.get("order_id"));
                                UUID tenantId = UUID.fromString(metadata.get("tenant_id"));

                                log.info("Processing Stripe mock payment capture for order: {} under tenant: {}", orderId, tenantId);
                                processCapture(tenantId, orderId, "STRIPE", sessionId, dataObject);
                            }
                        }
                    }
                }
            } else {
                Event event = com.stripe.net.Webhook.constructEvent(payload, sigHeader, stripeWebhookSecret);
                if ("checkout.session.completed".equals(event.getType()) || "payment_intent.succeeded".equals(event.getType())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataObject = (Map<String, Object>) event.getDataObjectDeserializer().getObject()
                            .map(obj -> objectMapper.convertValue(obj, Map.class))
                            .orElse(null);

                    if (dataObject != null) {
                        String sessionId = (String) dataObject.get("id");
                        @SuppressWarnings("unchecked")
                        Map<String, String> metadata = (Map<String, String>) dataObject.get("metadata");

                        if (metadata != null && metadata.containsKey("order_id") && metadata.containsKey("tenant_id")) {
                            UUID orderId = UUID.fromString(metadata.get("order_id"));
                            UUID tenantId = UUID.fromString(metadata.get("tenant_id"));

                            log.info("Processing Stripe payment capture for order: {} under tenant: {}", orderId, tenantId);
                            processCapture(tenantId, orderId, "STRIPE", sessionId, dataObject);
                        }
                    }
                }
            }

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            log.error("Stripe webhook processing failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/paystack")
    @Transactional
    public ResponseEntity<String> handlePaystackWebhook(
            @RequestBody String payload,
            @RequestHeader("x-paystack-signature") String sigHeader) {
        log.info("Received Paystack webhook");

        try {
            if (!paystackSecretKey.startsWith("mock_")) {
                String computedSignature = calculateHmacSha512(payload, paystackSecretKey);
                if (!computedSignature.equalsIgnoreCase(sigHeader)) {
                    log.warn("Invalid Paystack webhook signature");
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(payload, Map.class);
            String event = (String) body.get("event");

            if ("charge.success".equals(event)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                if (data != null) {
                    String reference = (String) data.get("reference");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");

                    if (metadata != null && metadata.containsKey("order_id") && metadata.containsKey("tenant_id")) {
                        UUID orderId = UUID.fromString(String.valueOf(metadata.get("order_id")));
                        UUID tenantId = UUID.fromString(String.valueOf(metadata.get("tenant_id")));

                        log.info("Processing Paystack payment capture for order: {} under tenant: {}", orderId, tenantId);
                        processCapture(tenantId, orderId, "PAYSTACK", reference, body);
                    }
                }
            }

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            log.error("Paystack webhook processing failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/flutterwave")
    @Transactional
    public ResponseEntity<String> handleFlutterwaveWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "verif-hash", required = false) String sigHeader) {
        log.info("Received Flutterwave webhook");

        try {
            if (!flutterwaveWebhookSecret.startsWith("mock_")) {
                if (sigHeader == null || !flutterwaveWebhookSecret.equals(sigHeader)) {
                    log.warn("Invalid Flutterwave webhook signature");
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(payload, Map.class);
            String event = (String) body.get("event");

            if ("charge.completed".equals(event)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                if (data != null) {
                    String reference = (String) data.get("tx_ref");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) data.get("meta");

                    if (meta != null && meta.containsKey("order_id") && meta.containsKey("tenant_id")) {
                        UUID orderId = UUID.fromString(String.valueOf(meta.get("order_id")));
                        UUID tenantId = UUID.fromString(String.valueOf(meta.get("tenant_id")));

                        log.info("Processing Flutterwave payment capture for order: {} under tenant: {}", orderId, tenantId);
                        processCapture(tenantId, orderId, "FLUTTERWAVE", reference, body);
                    }
                }
            }

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            log.error("Flutterwave webhook processing failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    private void processCapture(UUID tenantId, UUID orderId, String provider, String reference, Map<String, Object> rawPayload) throws Exception {
        TenantContext.setCurrentTenant(tenantId);
        try {
            Payment payment = paymentRepository.findByOrderIdAndTenantId(orderId, tenantId)
                    .orElseGet(() -> {
                        log.info("Dynamic payment record creation on webhook capture for order: {}", orderId);
                        BigDecimal amount = BigDecimal.ZERO;
                        String currency = "USD";
                        
                        try {
                            if ("STRIPE".equals(provider)) {
                                Number stripeAmt = (Number) rawPayload.get("amount");
                                if (stripeAmt != null) amount = BigDecimal.valueOf(stripeAmt.doubleValue()).divide(BigDecimal.valueOf(100));
                                String stripeCur = (String) rawPayload.get("currency");
                                if (stripeCur != null) currency = stripeCur.toUpperCase();
                            } else if ("PAYSTACK".equals(provider)) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> data = (Map<String, Object>) rawPayload.get("data");
                                if (data != null) {
                                    Number psAmt = (Number) data.get("amount");
                                    if (psAmt != null) amount = BigDecimal.valueOf(psAmt.doubleValue()).divide(BigDecimal.valueOf(100));
                                    String psCur = (String) data.get("currency");
                                    if (psCur != null) currency = psCur.toUpperCase();
                                }
                            } else if ("FLUTTERWAVE".equals(provider)) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> data = (Map<String, Object>) rawPayload.get("data");
                                if (data != null) {
                                    Number flwAmt = (Number) data.get("amount");
                                    if (flwAmt != null) amount = BigDecimal.valueOf(flwAmt.doubleValue());
                                    String flwCur = (String) data.get("currency");
                                    if (flwCur != null) currency = flwCur.toUpperCase();
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed parsing amount/currency from webhook payload", e);
                        }

                        Payment newPayment = new Payment(tenantId, orderId, amount, currency, provider, reference);
                        return paymentRepository.save(newPayment);
                    });

            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.CAPTURED);
                payment.setProviderReference(reference);
                paymentRepository.save(payment);

                PaymentCapturedEvent capturedEvent = new PaymentCapturedEvent(
                        orderId,
                        tenantId,
                        provider,
                        reference,
                        payment.getAmount(),
                        payment.getCurrency()
                );
                eventPublisher.publishEvent(capturedEvent);
                log.info("Successfully processed capture and published PaymentCapturedEvent for order: {}", orderId);
            } else {
                log.info("Payment for order: {} is already in status: {}", orderId, payment.getStatus());
            }
        } finally {
            TenantContext.clear();
        }
    }

    private String calculateHmacSha512(String data, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(secretKeySpec);
        byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
