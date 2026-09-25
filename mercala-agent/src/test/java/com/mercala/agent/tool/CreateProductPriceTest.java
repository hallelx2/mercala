package com.mercala.agent.tool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.client.MercalaCoreClient;
import com.mercala.agent.tool.ToolPayloads.CreateProductArgs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A product is not created without a price the merchant actually gave.
 *
 * <p>Asked for a bowl without naming a price, the model filled the gap with zero and the
 * storefront listed it at £0.00 — visible to shoppers, and indistinguishable from a bug in
 * the pricing code. The tool refuses and says what to do instead.
 */
class CreateProductPriceTest {

    private final MercalaCoreClient core = Mockito.mock(MercalaCoreClient.class);
    private final CatalogTools tools = new CatalogTools(core, new ProductLookup(core));

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void aProductWithNoPriceIsNotCreated() {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));

        Map<String, Object> result = tools.createProduct().apply(
                new CreateProductArgs("Serving bowl", "wide", null, null, List.of(), List.of()));

        assertThat(result.get("status")).isEqualTo("PRICE_REQUIRED");
        Mockito.verify(core, Mockito.never()).createProduct(Mockito.anyMap());
    }

    @Test
    void aProductPricedAtZeroIsNotCreatedEither() {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));

        Map<String, Object> result = tools.createProduct().apply(
                new CreateProductArgs("Serving bowl", "wide", BigDecimal.ZERO, null, List.of(), List.of()));

        assertThat(result.get("status")).isEqualTo("PRICE_REQUIRED");
        Mockito.verify(core, Mockito.never()).createProduct(Mockito.anyMap());
    }

    @Test
    void aPricedProductIsCreated() {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));
        Mockito.when(core.createProduct(Mockito.anyMap())).thenReturn(Map.of("id", UUID.randomUUID().toString()));

        Map<String, Object> result = tools.createProduct().apply(
                new CreateProductArgs("Serving bowl", "wide", new BigDecimal("120"), null, List.of(), List.of()));

        assertThat(result).containsKey("id");
        Mockito.verify(core).createProduct(Mockito.anyMap());
    }
}
