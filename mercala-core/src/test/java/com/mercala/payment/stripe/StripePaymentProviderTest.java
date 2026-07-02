package com.mercala.payment.stripe;

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

class StripePaymentProviderTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private ProviderRefRepository providerRefRepository;
    @Autowired private StripePaymentProvider stripePaymentProvider;

    @Test
    void initializesStripeCheckoutSessionMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("stripe-tenant-1", "Stripe Tenant 1"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "STRIPE", "acct_test123", true));

            PaymentRequest request = new PaymentRequest(
                    UUID.randomUUID(),
                    new BigDecimal("150.00"),
                    "USD",
                    "idemp-stripe-1",
                    "https://localhost/success"
            );

            PaymentResponse response = stripePaymentProvider.initializePayment(request);

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.providerReference()).startsWith("cs_test_");
            assertThat(response.checkoutUrl()).startsWith("https://checkout.stripe.com/c/pay/cs_test_");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void verifiesPaymentMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("stripe-tenant-2", "Stripe Tenant 2"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "STRIPE", "acct_test456", true));

            PaymentResponse response = stripePaymentProvider.verifyPayment("cs_test_some_id");

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("SUCCESSFUL");
            assertThat(response.providerReference()).isEqualTo("cs_test_some_id");
            assertThat(response.rawResponse()).contains("mock");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void refundsPaymentMockMode() {
        Tenant tenant = tenantRepository.save(new Tenant("stripe-tenant-3", "Stripe Tenant 3"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "STRIPE", "acct_test789", true));

            RefundRequest refundRequest = new RefundRequest(
                    UUID.randomUUID(),
                    "pi_test123",
                    new BigDecimal("50.00"),
                    "Customer returned item"
            );

            RefundResponse response = stripePaymentProvider.refundPayment(refundRequest);

            assertThat(response.success()).isTrue();
            assertThat(response.status()).isEqualTo("REFUNDED");
            assertThat(response.refundReference()).startsWith("re_mock_");
        } finally {
            TenantContext.clear();
        }
    }
}
