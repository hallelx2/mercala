package com.mercala.order;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.mercala.identity.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Order visibility rules.
 *
 * <p>Worth unit-testing rather than leaving to the integration suite: tenant isolation is
 * enforced by the Hibernate filter and RLS, but neither of those separates one shopper
 * from another <em>within</em> a tenant. That distinction lives only in this class, so a
 * regression here leaks one customer's order to another with every other defence still
 * intact and reporting healthy.
 */
class OrderQueryServiceTest {

    private final OrderRepository repository = Mockito.mock(OrderRepository.class);
    private final OrderQueryService service = new OrderQueryService(repository);

    private final UUID shopperId = UUID.randomUUID();
    private final UUID otherShopperId = UUID.randomUUID();
    private final Pageable page = PageRequest.of(0, 20);

    private static Order orderOwnedBy(UUID userId) {
        return new Order(UUID.randomUUID(), userId, new BigDecimal("42.00"), null);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MERCHANT_OWNER", "MERCHANT_STAFF", "PLATFORM_ADMIN"})
    void merchantRolesSeeEveryOrderInTheTenant(Role role) {
        when(repository.findAllBy(any(Pageable.class))).thenReturn(Page.empty());

        service.findVisible(shopperId, role, null, page);

        verify(repository).findAllBy(page);
        verify(repository, never()).findByUserId(any(), any());
    }

    @Test
    void shopperOnlySeesTheirOwnOrders() {
        when(repository.findByUserId(eq(shopperId), any(Pageable.class))).thenReturn(Page.empty());

        service.findVisible(shopperId, Role.SHOPPER, null, page);

        verify(repository).findByUserId(shopperId, page);
        verify(repository, never()).findAllBy(any());
    }

    @Test
    void statusFilterIsAppliedWithinTheShopperScope() {
        when(repository.findByUserIdAndStatus(eq(shopperId), eq(OrderStatus.PLACED), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.findVisible(shopperId, Role.SHOPPER, OrderStatus.PLACED, page);

        // The status filter must narrow the shopper's own orders, never widen to the tenant.
        verify(repository).findByUserIdAndStatus(shopperId, OrderStatus.PLACED, page);
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void statusFilterIsAppliedAcrossTheTenantForMerchants() {
        when(repository.findByStatus(eq(OrderStatus.PLACED), any(Pageable.class))).thenReturn(Page.empty());

        service.findVisible(shopperId, Role.MERCHANT_OWNER, OrderStatus.PLACED, page);

        verify(repository).findByStatus(OrderStatus.PLACED, page);
    }

    @Test
    void shopperCannotReadAnotherShoppersOrderById() {
        Order someoneElses = orderOwnedBy(otherShopperId);
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.of(someoneElses));

        Optional<Order> result = service.findVisibleById(orderId, shopperId, Role.SHOPPER);

        assertThat(result)
                .as("another shopper's order must be invisible even though it is in the same tenant")
                .isEmpty();
    }

    @Test
    void shopperCanReadTheirOwnOrderById() {
        Order own = orderOwnedBy(shopperId);
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.of(own));

        assertThat(service.findVisibleById(orderId, shopperId, Role.SHOPPER)).contains(own);
    }

    @Test
    void merchantCanReadAnyOrderInTheTenantById() {
        Order shoppersOrder = orderOwnedBy(otherShopperId);
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.of(shoppersOrder));

        assertThat(service.findVisibleById(orderId, shopperId, Role.MERCHANT_OWNER)).contains(shoppersOrder);
    }

    @Test
    void missingOrderIsEmptyRatherThanAnError() {
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.empty());

        // Absent and forbidden collapse to the same result on purpose — the controller turns
        // both into 404, so a 403 never confirms that someone else's order id exists.
        assertThat(service.findVisibleById(orderId, shopperId, Role.SHOPPER)).isEmpty();
        assertThat(service.findVisibleById(orderId, shopperId, Role.MERCHANT_OWNER)).isEmpty();
    }

    @Test
    void pagingIsPassedThroughUnchanged() {
        Pageable secondPage = PageRequest.of(1, 5);
        when(repository.findByUserId(eq(shopperId), eq(secondPage)))
                .thenReturn(new PageImpl<>(java.util.List.of(), secondPage, 0));

        service.findVisible(shopperId, Role.SHOPPER, null, secondPage);

        verify(repository).findByUserId(shopperId, secondPage);
    }
}
