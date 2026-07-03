package com.mercala.payment;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.payment.stripe.StripePaymentProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.mercala.platform.multitenancy.TenantContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PaymentResilienceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ProviderRefRepository providerRefRepository;

    @Autowired
    private StripePaymentProvider stripePaymentProvider;

    @org.junit.jupiter.api.AfterEach
    void resetCircuitBreakers() {
        circuitBreakerRegistry.circuitBreaker("stripe-payment").reset();
    }

    @Test
    void verifiesPaymentCircuitBreakerTransitionsToOpenOnFailures() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("stripe-payment");
        assertThat(circuitBreaker).isNotNull();

        circuitBreaker.reset();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        Tenant tenant = tenantRepository.save(new Tenant("resilience-tenant", "Resilience Tenant"));
        TenantContext.setCurrentTenant(tenant.getId());

        try {
            // Save a provider ref with a non-mock secret key to trigger real API calls (which will fail because the key is invalid)
            ProviderRef providerRef = new ProviderRef(tenant.getId(), "STRIPE", "acct_some_id", true);
            providerRef.setSecretKey("real-but-invalid-key-to-force-failure");
            providerRefRepository.save(providerRef);

            PaymentRequest request = new PaymentRequest(
                    UUID.randomUUID(),
                    new BigDecimal("100.00"),
                    "USD",
                    "idemp-resilience-1",
                    "https://localhost/success"
            );

            // Make 5 calls. Each should fail and return a fallback failed response
            for (int i = 0; i < 5; i++) {
                PaymentResponse response = stripePaymentProvider.initializePayment(request);
                assertThat(response.success()).isFalse();
                assertThat(response.status()).isEqualTo("FAILED");
                assertThat(response.errorMessage()).contains("Stripe service temporarily unavailable");
            }

            // Verify circuit is now OPEN
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // A 6th call should immediately trigger fallback and not call Stripe
            PaymentResponse response = stripePaymentProvider.initializePayment(request);
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("Stripe service temporarily unavailable");

        } finally {
            TenantContext.clear();
        }
    }
}
