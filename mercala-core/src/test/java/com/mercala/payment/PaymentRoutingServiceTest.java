package com.mercala.payment;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.payment.flutterwave.FlutterwavePaymentProvider;
import com.mercala.payment.paystack.PaystackPaymentProvider;
import com.mercala.payment.stripe.StripePaymentProvider;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRoutingServiceTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private ProviderRefRepository providerRefRepository;
    @Autowired private PaymentRoutingService paymentRoutingService;

    @Autowired private StripePaymentProvider stripePaymentProvider;
    @Autowired private PaystackPaymentProvider paystackPaymentProvider;
    @Autowired private FlutterwavePaymentProvider flutterwavePaymentProvider;

    @Test
    void routesByTenantRegionDefault() {
        Tenant usTenant = tenantRepository.save(new Tenant("route-us-slug", "US Store", "US"));
        Tenant ngTenant = tenantRepository.save(new Tenant("route-ng-slug", "NG Store", "NG"));
        Tenant keTenant = tenantRepository.save(new Tenant("route-ke-slug", "KE Store", "KE"));

        TenantContext.setCurrentTenant(usTenant.getId());
        try {
            PaymentProvider provider = paymentRoutingService.getProvider(null);
            assertThat(provider).isSameAs(stripePaymentProvider);
        } finally {
            TenantContext.clear();
        }

        TenantContext.setCurrentTenant(ngTenant.getId());
        try {
            PaymentProvider provider = paymentRoutingService.getProvider(null);
            assertThat(provider).isSameAs(paystackPaymentProvider);
        } finally {
            TenantContext.clear();
        }

        TenantContext.setCurrentTenant(keTenant.getId());
        try {
            PaymentProvider provider = paymentRoutingService.getProvider(null);
            assertThat(provider).isSameAs(flutterwavePaymentProvider);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void routesByTenantExplicitConfigOverride() {
        Tenant tenant = tenantRepository.save(new Tenant("route-override-slug", "Custom Store", "US"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "PAYSTACK", "mock_ps_key", true));

            PaymentProvider provider = paymentRoutingService.getProvider(null);
            assertThat(provider).isSameAs(paystackPaymentProvider);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void routesByExplicitPreferredRequestArgument() {
        Tenant tenant = tenantRepository.save(new Tenant("route-pref-slug", "Pref Store", "US"));
        
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            providerRefRepository.save(new ProviderRef(tenant.getId(), "STRIPE", "mock_stripe", true));
            providerRefRepository.save(new ProviderRef(tenant.getId(), "FLUTTERWAVE", "mock_flw", true));

            PaymentProvider defaultProvider = paymentRoutingService.getProvider(null);
            assertThat(defaultProvider).isSameAs(stripePaymentProvider);

            PaymentProvider preferredProvider = paymentRoutingService.getProvider("FLUTTERWAVE");
            assertThat(preferredProvider).isSameAs(flutterwavePaymentProvider);
        } finally {
            TenantContext.clear();
        }
    }
}
