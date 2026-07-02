package com.mercala.payment.web;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.order.Order;
import com.mercala.order.OrderRepository;
import com.mercala.order.OrderStatus;
import com.mercala.payment.Payment;
import com.mercala.payment.PaymentRepository;
import com.mercala.payment.PaymentStatus;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebhookControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    void handlesStripeWebhookSuccessfully() throws Exception {
        Tenant tenant = tenantRepository.save(new Tenant("webhook-stripe-tenant", "Webhook Stripe Tenant"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        Order order;
        Payment payment;
        try {
            order = orderRepository.save(new Order(tenant.getId(), UUID.randomUUID(), new BigDecimal("100.00"), "idemp-stripe"));
            payment = paymentRepository.save(new Payment(tenant.getId(), order.getId(), new BigDecimal("100.00"), "USD", "STRIPE", "idemp-stripe"));
        } finally {
            TenantContext.clear();
        }

        String stripePayload = """
        {
            "id": "evt_test_123",
            "type": "checkout.session.completed",
            "api_version": "%s",
            "data": {
                "object": {
                    "id": "cs_test_abc",
                    "amount": 10000,
                    "currency": "usd",
                    "metadata": {
                        "order_id": "%s",
                        "tenant_id": "%s"
                    }
                }
            }
        }
        """.formatted(com.stripe.Stripe.API_VERSION, order.getId(), tenant.getId());

        mockMvc.perform(post("/api/webhooks/stripe")
                .header("Stripe-Signature", "t=123,v1=mock_sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stripePayload))
                .andExpect(status().isOk());

        TenantContext.setCurrentTenant(tenant.getId());
        try {
            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(updatedPayment.getProviderReference()).isEqualTo("cs_test_abc");

            Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void handlesPaystackWebhookSuccessfully() throws Exception {
        Tenant tenant = tenantRepository.save(new Tenant("webhook-paystack-tenant", "Webhook Paystack Tenant"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        Order order;
        Payment payment;
        try {
            order = orderRepository.save(new Order(tenant.getId(), UUID.randomUUID(), new BigDecimal("200.00"), "idemp-paystack"));
            payment = paymentRepository.save(new Payment(tenant.getId(), order.getId(), new BigDecimal("200.00"), "NGN", "PAYSTACK", "idemp-paystack"));
        } finally {
            TenantContext.clear();
        }

        String paystackPayload = """
        {
            "event": "charge.success",
            "data": {
                "reference": "pstk_test_123",
                "amount": 20000,
                "currency": "NGN",
                "metadata": {
                    "order_id": "%s",
                    "tenant_id": "%s"
                }
            }
        }
        """.formatted(order.getId(), tenant.getId());

        mockMvc.perform(post("/api/webhooks/paystack")
                .header("x-paystack-signature", "mock_sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paystackPayload))
                .andExpect(status().isOk());

        TenantContext.setCurrentTenant(tenant.getId());
        try {
            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(updatedPayment.getProviderReference()).isEqualTo("pstk_test_123");

            Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void handlesFlutterwaveWebhookSuccessfully() throws Exception {
        Tenant tenant = tenantRepository.save(new Tenant("webhook-flutterwave-tenant", "Webhook Flutterwave Tenant"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        Order order;
        Payment payment;
        try {
            order = orderRepository.save(new Order(tenant.getId(), UUID.randomUUID(), new BigDecimal("300.00"), "idemp-flutterwave"));
            payment = paymentRepository.save(new Payment(tenant.getId(), order.getId(), new BigDecimal("300.00"), "USD", "FLUTTERWAVE", "idemp-flutterwave"));
        } finally {
            TenantContext.clear();
        }

        String flutterwavePayload = """
        {
            "event": "charge.completed",
            "data": {
                "tx_ref": "flw_test_123",
                "amount": 300.0,
                "currency": "USD",
                "meta": {
                    "order_id": "%s",
                    "tenant_id": "%s"
                }
            }
        }
        """.formatted(order.getId(), tenant.getId());

        mockMvc.perform(post("/api/webhooks/flutterwave")
                .header("verif-hash", "mock_sig")
                .contentType(MediaType.APPLICATION_JSON)
                .content(flutterwavePayload))
                .andExpect(status().isOk());

        TenantContext.setCurrentTenant(tenant.getId());
        try {
            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(updatedPayment.getProviderReference()).isEqualTo("flw_test_123");

            Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        } finally {
            TenantContext.clear();
        }
    }
}
