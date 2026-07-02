package com.mercala.payment.paystack;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.payment.PaymentRequest;
import com.mercala.payment.PaymentResponse;
import com.mercala.payment.ProviderRef;
import com.mercala.payment.ProviderRefRepository;
import com.mercala.payment.RefundRequest;
import com.mercala.payment.RefundResponse;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

class PaystackPaymentProviderTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private ProviderRefRepository providerRefRepository;
    @Autowired private PaystackPaymentProvider paystackPaymentProvider;

    @Test
    void initializesPaystackTransactionMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("paystack-tenant-1", "Paystack Tenant 1"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "PAYSTACK", "mock_key_123", true));

            PaymentRequest request = new PaymentRequest(
                    UUID.randomUUID(),
                    new BigDecimal("250.00"),
                    "NGN",
                    "idemp-pstk-1",
                    "https://localhost/callback"
            );

            PaymentResponse response = paystackPaymentProvider.initializePayment(request);

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.providerReference()).startsWith("pstk_");
            assertThat(response.checkoutUrl()).startsWith("https://checkout.paystack.com/pstk_");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void verifiesPaystackTransactionMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("paystack-tenant-2", "Paystack Tenant 2"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "PAYSTACK", "mock_key_456", true));

            PaymentResponse response = paystackPaymentProvider.verifyPayment("pstk_some_id");

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("SUCCESSFUL");
            assertThat(response.providerReference()).isEqualTo("pstk_some_id");
            assertThat(response.rawResponse()).contains("mock");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void refundsPaystackTransactionMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("paystack-tenant-3", "Paystack Tenant 3"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "PAYSTACK", "mock_key_789", true));

            RefundRequest refundRequest = new RefundRequest(
                    UUID.randomUUID(),
                    "pstk_trx_123",
                    new BigDecimal("100.00"),
                    "Item returned"
            );

            RefundResponse response = paystackPaymentProvider.refundPayment(refundRequest);

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("REFUNDED");
            assertThat(response.refundReference()).startsWith("re_mock_ps_");
        } finally {
            TenantContext.clear();
        }
    }
}
