package com.mercala.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercala.agent.agui.AgentRunChannel;
import com.mercala.agent.chat.AgentContext;

/**
 * Wraps a tool body so the run's client sees it happen.
 *
 * <p>Every tool goes through here, which is the point: a tool that forgot to report would
 * be invisible during the slowest seconds of a turn, and "invisible" is indistinguishable
 * from "hung" to the person waiting. One wrapper means one place where the AG-UI lifecycle
 * is correct rather than six places where it is nearly correct.
 *
 * <p>Failures are reported, not swallowed and not rethrown. A tool that throws produces a
 * result frame marked as an error and a map the model can read, because the model recovering
 * ("that SKU already exists — want me to update it instead?") is better than the run dying.
 */
public final class ToolActivity {

    private static final Logger log = LoggerFactory.getLogger(ToolActivity.class);

    /**
     * Argument serialisation only — deliberately not the Spring context's mapper. Tool
     * arguments are plain records, and borrowing an application-configured mapper would
     * let a future serialisation setting change what the protocol puts on the wire.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolActivity() {
    }

    /**
     * Runs {@code body}, framing it with the AG-UI tool-call lifecycle.
     *
     * @param toolName the name the model called, as declared to it
     * @param arguments the tool's argument record, serialised for the client
     * @param body the actual work
     * @return the tool's result, or a structured error if it threw
     */
    public static Map<String, Object> observe(
            String toolName, Object arguments, Supplier<Map<String, Object>> body) {

        AgentRunChannel channel = AgentContext.currentChannel();
        String toolCallId = channel.nextToolCallId();
        channel.toolStarted(toolCallId, toolName, toJson(arguments));

        long startedAt = System.nanoTime();
        try {
            Map<String, Object> result = body.get();
            long elapsedMs = elapsedMsSince(startedAt);
            channel.toolFinished(toolCallId, toJson(result), elapsedMs, false);
            return result;
        } catch (RuntimeException e) {
            long elapsedMs = elapsedMsSince(startedAt);
            log.error("Tool {} failed after {}ms", toolName, elapsedMs, e);

            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("status", "FAILED");
            failure.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            channel.toolFinished(toolCallId, toJson(failure), elapsedMs, true);
            return failure;
        }
    }

    private static long elapsedMsSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * A tool argument that will not serialise must not take the tool down with it — the
     * client loses one detail panel, which is a far smaller loss than the action itself.
     */
    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise tool payload of type {}: {}",
                    value.getClass().getSimpleName(), e.getMessage());
            return "{\"unserialisable\":\"" + value.getClass().getSimpleName() + "\"}";
        }
    }
}
