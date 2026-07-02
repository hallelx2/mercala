package com.mercala.order;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateTransitionTest {

    @Test
    void startsInPlacedState() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "key");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void transitionsFromPlacedToPaid() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "key");
        order.transitionTo(OrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void transitionsFromPlacedToCancelled() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "key");
        order.transitionTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void transitionsFromPaidToFulfilled() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "key");
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.FULFILLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
    }

    @Test
    void transitionsFromPaidToCancelled() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "key");
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void rejectsInvalidTransitions() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "key");
        
        // Cannot go from PLACED directly to FULFILLED
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.FULFILLED))
                .isInstanceOf(IllegalStateException.class);
        
        // Transition to PAID then CANCELLED
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.CANCELLED);
        
        // Terminal states cannot transition further
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.PAID))
                .isInstanceOf(IllegalStateException.class);
    }
}
