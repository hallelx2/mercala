package com.mercala.agent.agui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A tool the browser declared and the browser will run.
 *
 * <p>This is the half of AG-UI that inverts the usual arrangement: the frontend decides
 * what the agent can do. It can offer {@code navigateTo} on a page where navigation makes
 * sense, {@code applyFilter} only while a filter panel is mounted, {@code openProduct} only
 * to a merchant with permission — and none of that requires a server deploy, because the
 * capability list is part of the run request.
 *
 * <p>Execution cannot happen here, so it does not pretend to. The callback emits the tool
 * call, tells the client to run it, and returns a sentinel telling the model to stop.
 * Whatever the browser produces comes back as a {@code tool} message on the next run, which
 * is the same round trip the human-in-the-loop tools use — one mechanism, not two.
 */
public final class FrontendToolCallback implements FunctionCallback {

    private static final Logger log = LoggerFactory.getLogger(FrontendToolCallback.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The schema handed to the model when the client declared none. */
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final RunAgentInput.ToolDefinition definition;
    private final AgentRunChannel channel;
    private final String schemaJson;

    public FrontendToolCallback(RunAgentInput.ToolDefinition definition, AgentRunChannel channel) {
        this.definition = definition;
        this.channel = channel;
        this.schemaJson = toSchemaJson(definition.parameters());
    }

    @Override
    public String getName() {
        return definition.name();
    }

    @Override
    public String getDescription() {
        return definition.description() == null ? definition.name() : definition.description();
    }

    @Override
    public String getInputTypeSchema() {
        return schemaJson;
    }

    @Override
    public String call(String functionArguments) {
        String toolCallId = channel.nextToolCallId();
        log.info("Dispatching client-declared tool {} to the browser as {}", definition.name(), toolCallId);

        channel.toolStarted(toolCallId, definition.name(), functionArguments);
        channel.custom("client_tool_dispatch", Map.of(
                "toolCallId", toolCallId,
                "name", definition.name(),
                "arguments", functionArguments == null ? "{}" : functionArguments));

        Map<String, Object> sentinel = new LinkedHashMap<>();
        sentinel.put("status", "DISPATCHED_TO_CLIENT");
        sentinel.put("toolCallId", toolCallId);
        sentinel.put("awaitingUser", true);
        sentinel.put("instruction",
                "This tool runs in the merchant's browser and has not returned yet. Stop here. "
                        + "Do not call it again and do not invent its result — the outcome will be "
                        + "given to you as a tool result on the next turn.");

        String rendered = write(sentinel);
        // The dispatch is complete work from this side: the client has been told to run it.
        // Reporting it as pending forever would leave the activity panel permanently spinning.
        channel.toolFinished(toolCallId, rendered, 0L, false);
        return rendered;
    }

    @Override
    public String call(String functionArguments, ToolContext toolContext) {
        return call(functionArguments);
    }

    /**
     * The model needs a JSON Schema string. A client that sent none still gets a valid
     * empty-object schema — a malformed schema would make the whole run fail on a tool the
     * model may never have chosen to call.
     */
    private static String toSchemaJson(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return EMPTY_SCHEMA;
        }
        try {
            return MAPPER.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            log.warn("Client tool schema could not be serialised; falling back to an empty object schema", e);
            return EMPTY_SCHEMA;
        }
    }

    private static String write(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"DISPATCHED_TO_CLIENT\"}";
        }
    }
}
