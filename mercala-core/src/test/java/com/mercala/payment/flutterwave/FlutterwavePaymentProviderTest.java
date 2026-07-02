package com.mercala.payment.flutterwave;

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

class FlutterwavePaymentProviderTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private ProviderRefRepository providerRefRepository;
    @Autowired private FlutterwavePaymentProvider flutterwavePaymentProvider;

    @Test
    void initializesFlutterwavePaymentMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("flutterwave-tenant-1", "Flutterwave Tenant 1"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "FLUTTERWAVE", "mock_key_123", true));

            PaymentRequest request = new PaymentRequest(
                    UUID.randomUUID(),
                    new BigDecimal("350.00"),
                    "USD",
                    "idemp-flw-1",
                    "https://localhost/callback"
            );

            PaymentResponse response = flutterwavePaymentProvider.initializePayment(request);

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.providerReference()).startsWith("flw_");
            assertThat(response.checkoutUrl()).startsWith("https://checkout.flutterwave.com/pay/flw_");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void verifiesFlutterwaveTransactionMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("flutterwave-tenant-2", "Flutterwave Tenant 2"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "FLUTTERWAVE", "mock_key_456", true));

            PaymentResponse response = flutterwavePaymentProvider.verifyPayment("flw_some_id");

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("SUCCESSFUL");
            assertThat(response.providerReference()).isEqualTo("flw_some_id");
            assertThat(response.rawResponse()).contains("mock");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void refundsFlutterwaveTransactionMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("flutterwave-tenant-3", "Flutterwave Tenant 3"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "FLUTTERWAVE", "mock_key_789", true));

            RefundRequest refundRequest = new RefundRequest(
                    UUID.randomUUID(),
                    "flw_trx_123",
                    new BigDecimal("150.00"),
                    "Item damaged"
            );

            RefundResponse response = flutterwavePaymentProvider.refundPayment(refundRequest);

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("REFUNDED");
            assertThat(response.refundReference()).startsWith("re_mock_flw_");
        } finally {
            TenantContext.clear();
        }
    }
}
