package com.mercala.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mercala.agent.agui.AgUiEvent;
import com.mercala.agent.agui.AgentRunChannel;
import com.mercala.agent.chat.AgentContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper every tool goes through. Its job is to make a tool's reality visible — which
 * means reporting the outcome that actually happened, including the one nobody wants.
 */
class ToolActivityTest {

    private AgentRunChannel channel;

    private void withRun() {
        channel = AgentRunChannel.active("run-1");
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER", channel));
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private List<AgUiEvent> drain() {
        channel.close();
        List<AgUiEvent> events = channel.events().collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    @Test
    void aSuccessfulToolReportsItsArgumentsAndItsResult() {
        withRun();

        Map<String, Object> result = ToolActivity.observe(
                "createProduct",
                new ToolPayloads.GetProductArgs("p-1"),
                () -> Map.of("id", "p-1", "name", "Linen shirt"));

        assertThat(result).containsEntry("name", "Linen shirt");

        List<AgUiEvent> events = drain();
        AgUiEvent.ToolCallStart start = (AgUiEvent.ToolCallStart) events.stream()
                .filter(AgUiEvent.ToolCallStart.class::isInstance).findFirst().orElseThrow();
        AgUiEvent.ToolCallArgs args = (AgUiEvent.ToolCallArgs) events.stream()
                .filter(AgUiEvent.ToolCallArgs.class::isInstance).findFirst().orElseThrow();
        AgUiEvent.ToolCallResult done = (AgUiEvent.ToolCallResult) events.stream()
                .filter(AgUiEvent.ToolCallResult.class::isInstance).findFirst().orElseThrow();

        assertThat(start.toolCallName()).isEqualTo("createProduct");
        assertThat(args.delta()).contains("p-1");
        assertThat(done.content()).contains("Linen shirt");
        assertThat(done.error()).isNull();
    }

    /**
     * A throwing tool must not take the run with it. The model can recover from "that SKU
     * already exists" — it cannot recover from a dead stream, and neither can the merchant.
     */
    @Test
    void aThrowingToolBecomesAnErrorResultRatherThanAFailedRun() {
        withRun();

        Map<String, Object> result = ToolActivity.observe(
                "updateInventory",
                new ToolPayloads.UpdateInventoryArgs("v-1", 5),
                () -> {
                    throw new IllegalStateException("variant not found");
                });

        assertThat(result).containsEntry("status", "FAILED");
        assertThat(result).containsEntry("error", "variant not found");

        AgUiEvent.ToolCallResult reported = (AgUiEvent.ToolCallResult) drain().stream()
                .filter(AgUiEvent.ToolCallResult.class::isInstance).findFirst().orElseThrow();

        assertThat(reported.error()).isTrue();
        assertThat(reported.content()).contains("variant not found");
    }

    /** An exception with no message would otherwise report an error of {@code null}. */
    @Test
    void aFailureWithNoMessageStillNamesSomething() {
        withRun();

        Map<String, Object> result = ToolActivity.observe(
                "getProduct",
                new ToolPayloads.GetProductArgs("p-1"),
                () -> {
                    throw new IllegalArgumentException();
                });

        assertThat(result.get("error")).isEqualTo("IllegalArgumentException");
    }

    /**
     * The blocking {@code /chat} endpoint and every unit test run tools with no channel
     * anywhere. Reporting has to be a no-op there, not a failure — otherwise the wrapper
     * would be a reason not to wrap.
     */
    @Test
    void aToolRunWithNoActiveRunStillWorks() {
        AgentContext.clear();

        Map<String, Object> result = ToolActivity.observe(
                "searchCatalog",
                new ToolPayloads.SearchCatalogArgs("linen", null, 0, 10),
                () -> Map.of("totalElements", 3));

        assertThat(result).containsEntry("totalElements", 3);
    }

    @Test
    void durationIsMeasuredAndReported() {
        withRun();

        ToolActivity.observe("getProduct", new ToolPayloads.GetProductArgs("p-1"), () -> {
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("id", "p-1");
        });

        AgUiEvent.ToolCallResult result = (AgUiEvent.ToolCallResult) drain().stream()
                .filter(AgUiEvent.ToolCallResult.class::isInstance).findFirst().orElseThrow();

        assertThat(result.durationMs()).isGreaterThanOrEqualTo(10L);
    }
}
