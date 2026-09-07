package com.mercala.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.AppUser;
import com.mercala.identity.AppUserRepository;
import com.mercala.identity.Role;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.order.Order;
import com.mercala.order.OrderRepository;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The missing link, under test.
 *
 * <p>Three providers implemented {@code initializePayment} and nothing ever called it, so
 * checkout reached PLACED and reserved stock without asking anyone for money. These tests
 * assert the behaviours that gap made impossible: that an attempt is recorded, that a repeat
 * does not charge twice, and that a provider being down does not lose the order.
 *
 * <p>Every case builds a real order first. {@code payments} carries a composite foreign key
 * on {@code (order_id, tenant_id)}, so a synthetic order id is rejected by the database —
 * correctly, since a payment for an order that does not exist is meaningless.
 */
class PaymentInitiationServiceTest extends AbstractIntegrationTest {

    @Autowired private PaymentInitiationService paymentInitiationService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private record Fixture(UUID tenantId, UUID orderId, String idempotencyKey) {}

    /** A tenant, a user, and a committed order in that tenant. */
    private Fixture placedOrderIn(String region, BigDecimal amount) {
        String slug = "store-" + UUID.randomUUID();
        // (slug, name, region) — the two-arg overload is (slug, name) and would leave region
        // null, which silently routes everything to the Stripe default.
        Tenant tenant = tenantRepository.save(new Tenant(slug, "Store " + slug, region));

        AppUser user = userRepository.save(new AppUser(
                tenant.getId(), slug + "@example.test",
                passwordEncoder.encode("password"), Role.SHOPPER));

        String key = "idem-" + UUID.randomUUID();
        TenantContext.setCurrentTenant(tenant.getId());
        try {
            Order order = orderRepository.save(
                    new Order(tenant.getId(), user.getId(), amount, key));
            return new Fixture(tenant.getId(), order.getId(), key);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void initiatingRecordsAnAttemptAgainstTheOrder() {
        Fixture f = placedOrderIn("GB", new BigDecimal("49.00"));

        var initiation = paymentInitiationService.initiateForOrder(
                f.tenantId(), f.orderId(), new BigDecimal("49.00"), f.idempotencyKey(), null);

        assertThat(initiation).isNotNull();
        assertThat(initiation.response()).isNotNull();

        // Whether the provider succeeds depends on whether test credentials reach it. What
        // must hold either way is that the attempt exists and is attributed, because the
        // inbound webhook looks the payment up by order id and finds nothing otherwise.
        TenantContext.setCurrentTenant(f.tenantId());
        try {
            var stored = paymentRepository.findByOrderIdAndTenantId(f.orderId(), f.tenantId());
            assertThat(stored).as("an attempt must be recorded for the webhook to match on").isPresent();
            assertThat(stored.get().getAmount()).isEqualByComparingTo("49.00");
            assertThat(stored.get().getProvider()).isNotBlank();
            assertThat(stored.get().getStatus()).isIn(PaymentStatus.PENDING, PaymentStatus.FAILED);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void initiatingTwiceDoesNotChargeTwice() {
        Fixture f = placedOrderIn("GB", new BigDecimal("49.00"));

        paymentInitiationService.initiateForOrder(
                f.tenantId(), f.orderId(), new BigDecimal("49.00"), f.idempotencyKey(), null);
        var second = paymentInitiationService.initiateForOrder(
                f.tenantId(), f.orderId(), new BigDecimal("49.00"), f.idempotencyKey(), null);

        assertThat(second.response().checkoutUrl())
                .as("a repeat must not mint a second checkout session")
                .isNull();

        TenantContext.setCurrentTenant(f.tenantId());
        try {
            assertThat(paymentRepository.findAll().stream()
                    .filter(p -> p.getOrderId().equals(f.orderId()))
                    .count())
                    .as("exactly one payment row per order")
                    .isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aProviderFailureDoesNotLoseTheOrder() {
        Fixture f = placedOrderIn("ZZ", new BigDecimal("10.00"));

        // An unknown region falls through to the Stripe default. Whatever the provider does,
        // this must come back as a result rather than an exception, because the order is
        // already committed and the stock is already reserved.
        var initiation = paymentInitiationService.initiateForOrder(
                f.tenantId(), f.orderId(), new BigDecimal("10.00"), f.idempotencyKey(), null);

        assertThat(initiation).isNotNull();
        TenantContext.setCurrentTenant(f.tenantId());
        try {
            assertThat(paymentRepository.findByOrderIdAndTenantId(f.orderId(), f.tenantId()))
                    .as("even a failed attempt is recorded, so it can be retried or reconciled")
                    .isPresent();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The regression that actually bit: {@code OrderPlacedEvent} is handled AFTER_COMMIT on the
     * request thread and clears {@code TenantContext} in its finally block, so by the time the
     * controller initiates payment the ThreadLocal is empty. Initiation must not depend on it.
     */
    @Test
    void worksWhenTheThreadLocalTenantHasAlreadyBeenCleared() {
        Fixture f = placedOrderIn("GB", new BigDecimal("25.00"));
        TenantContext.clear();

        var initiation = paymentInitiationService.initiateForOrder(
                f.tenantId(), f.orderId(), new BigDecimal("25.00"), f.idempotencyKey(), null);

        assertThat(initiation).isNotNull();
        assertThat(TenantContext.getCurrentTenant())
                .as("the ThreadLocal must be left exactly as it was found")
                .isNull();
    }

    @Test
    void refusesToInitiateWithoutATenant() {
        assertThatThrownBy(() -> paymentInitiationService.initiateForOrder(
                null, UUID.randomUUID(), new BigDecimal("1.00"), "k", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant context");
    }
}
