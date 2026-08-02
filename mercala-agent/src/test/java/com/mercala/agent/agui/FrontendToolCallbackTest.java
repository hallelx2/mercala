package com.mercala.agent.agui;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client-declared tools are the inversion at the centre of AG-UI: the browser decides what
 * the agent can do this run. Since the work happens over there, everything this class does
 * is about handing over cleanly and not lying to the model in the meantime.
 */
class FrontendToolCallbackTest {

    private static RunAgentInput.ToolDefinition definition(Map<String, Object> schema) {
        return new RunAgentInput.ToolDefinition(
                "navigateTo", "Open a page in the merchant dashboard", schema);
    }

    @Test
    void callingItDispatchesToTheClientAndTellsTheModelToWait() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");
        FrontendToolCallback callback = new FrontendToolCallback(definition(Map.of()), channel);

        String result = callback.call("{\"path\":\"/dashboard/products\"}");
        channel.close();

        assertThat(result).contains("DISPATCHED_TO_CLIENT").contains("awaitingUser");

        List<AgUiEvent> events = channel.events().collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(AgUiEvent.ToolCallStart.class::isInstance);
        assertThat(events).anyMatch(event -> event instanceof AgUiEvent.Custom custom
                && "client_tool_dispatch".equals(custom.name()));
    }

    @Test
    void theDispatchedArgumentsAreForwardedVerbatim() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");
        FrontendToolCallback callback = new FrontendToolCallback(definition(Map.of()), channel);

        callback.call("{\"path\":\"/dashboard/products\"}");
        channel.close();

        AgUiEvent.Custom dispatch = channel.events()
                .filter(AgUiEvent.Custom.class::isInstance)
                .map(AgUiEvent.Custom.class::cast)
                .blockFirst();

        assertThat(dispatch).isNotNull();
        assertThat(dispatch.value().get("arguments")).isEqualTo("{\"path\":\"/dashboard/products\"}");
        assertThat(dispatch.value().get("name")).isEqualTo("navigateTo");
    }

    @Test
    void theClientsSchemaIsWhatTheModelSees() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")));

        FrontendToolCallback callback =
                new FrontendToolCallback(definition(schema), AgentRunChannel.inactive());

        assertThat(callback.getInputTypeSchema()).contains("\"path\"").contains("\"object\"");
    }

    /**
     * A client that declared a tool without parameters must not break the run — the model
     * may never even choose to call it, and a malformed schema would fail the whole turn.
     */
    @Test
    void aToolWithNoSchemaStillGetsAValidOne() {
        FrontendToolCallback callback =
                new FrontendToolCallback(definition(null), AgentRunChannel.inactive());

        assertThat(callback.getInputTypeSchema()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
    }

    @Test
    void aToolWithNoDescriptionFallsBackToItsName() {
        FrontendToolCallback callback = new FrontendToolCallback(
                new RunAgentInput.ToolDefinition("navigateTo", null, Map.of()),
                AgentRunChannel.inactive());

        assertThat(callback.getDescription()).isEqualTo("navigateTo");
    }

    /**
     * Dispatching is finished work from this side. Leaving the call open would park the
     * client's activity panel on a spinner that nothing will ever clear.
     */
    @Test
    void theDispatchIsReportedAsCompleteRatherThanLeftRunning() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");
        new FrontendToolCallback(definition(Map.of()), channel).call("{}");
        channel.close();

        AgUiEvent.ToolCallResult result = channel.events()
                .filter(AgUiEvent.ToolCallResult.class::isInstance)
                .map(AgUiEvent.ToolCallResult.class::cast)
                .blockFirst();

        assertThat(result).isNotNull();
        assertThat(result.error()).isNull();
    }
}
