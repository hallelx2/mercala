package com.mercala.agent.chat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for AgentContext ThreadLocal lifecycle.
 */
class AgentContextTest {

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    @Test
    void current_throwsWhenNotSet() {
        assertThatThrownBy(AgentContext::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentContext not set");
    }

    @Test
    void setAndCurrent_roundTrips() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentContext ctx = new AgentContext(tenantId, userId, "MERCHANT_OWNER");

        AgentContext.set(ctx);

        AgentContext retrieved = AgentContext.current();
        assertThat(retrieved.tenantId()).isEqualTo(tenantId);
        assertThat(retrieved.userId()).isEqualTo(userId);
        assertThat(retrieved.userRole()).isEqualTo("MERCHANT_OWNER");
    }

    @Test
    void clear_removesContext() {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "SHOPPER"));
        AgentContext.clear();

        assertThatThrownBy(AgentContext::current)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void set_overwritesPreviousContext() {
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();

        AgentContext.set(new AgentContext(firstTenant, UUID.randomUUID(), "MERCHANT_OWNER"));
        AgentContext.set(new AgentContext(secondTenant, UUID.randomUUID(), "MERCHANT_STAFF"));

        assertThat(AgentContext.current().tenantId()).isEqualTo(secondTenant);
        assertThat(AgentContext.current().userRole()).isEqualTo("MERCHANT_STAFF");
    }
}
