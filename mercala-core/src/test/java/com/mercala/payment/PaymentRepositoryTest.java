package com.mercala.payment;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mercala.AbstractIntegrationTest;
import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.order.Order;
import com.mercala.order.OrderRepository;
import com.mercala.platform.multitenancy.TenantContext;

import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRepositoryTest extends AbstractIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void savesAndRetrievesPaymentWithTenantIsolation() {
        Tenant tenant1 = tenantRepository.save(new Tenant("pay-tenant-1", "Pay Tenant 1"));
        Tenant tenant2 = tenantRepository.save(new Tenant("pay-tenant-2", "Pay Tenant 2"));

        // 1. Create order and payment in tenant 1
        TenantContext.setCurrentTenant(tenant1.getId());
        UUID orderId1;
        UUID paymentId1;
        try {
            Order order1 = orderRepository.save(new Order(tenant1.getId(), UUID.randomUUID(), BigDecimal.TEN, "key-1"));
            orderId1 = order1.getId();

            Payment payment1 = paymentRepository.save(new Payment(
                    tenant1.getId(),
                    orderId1,
                    BigDecimal.TEN,
                    "USD",
                    "STRIPE",
                    "idemp-key-1"
            ));
            paymentId1 = payment1.getId();
        } finally {
            TenantContext.clear();
        }

        // 2. Create order and payment in tenant 2
        TenantContext.setCurrentTenant(tenant2.getId());
        UUID orderId2;
        UUID paymentId2;
        try {
            Order order2 = orderRepository.save(new Order(tenant2.getId(), UUID.randomUUID(), BigDecimal.TEN, "key-2"));
            orderId2 = order2.getId();

            Payment payment2 = paymentRepository.save(new Payment(
                    tenant2.getId(),
                    orderId2,
                    BigDecimal.TEN,
                    "USD",
                    "STRIPE",
                    "idemp-key-2"
            ));
            paymentId2 = payment2.getId();
        } finally {
            TenantContext.clear();
        }

        // 3. Verify tenant 1 cannot see tenant 2's payment, and vice versa
        transactionTemplate.execute(status -> {
            TenantContext.setCurrentTenant(tenant1.getId());
            try {
                assertThat(paymentRepository.findByIdAndTenantId(paymentId1, tenant1.getId())).isPresent();
                assertThat(paymentRepository.findByIdAndTenantId(paymentId2, tenant1.getId())).isEmpty();
                assertThat(paymentRepository.findByOrderIdAndTenantId(orderId1, tenant1.getId())).isPresent();
                assertThat(paymentRepository.findByOrderIdAndTenantId(orderId2, tenant1.getId())).isEmpty();
            } finally {
                TenantContext.clear();
            }
            return null;
        });

        transactionTemplate.execute(status -> {
            TenantContext.setCurrentTenant(tenant2.getId());
            try {
                assertThat(paymentRepository.findByIdAndTenantId(paymentId2, tenant2.getId())).isPresent();
                assertThat(paymentRepository.findByIdAndTenantId(paymentId1, tenant2.getId())).isEmpty();
            } finally {
                TenantContext.clear();
            }
            return null;
        });
    }
}
