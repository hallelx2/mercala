package com.mercala.agent.agui;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotEmpty;

/**
 * The AG-UI run request: everything the client knows, handed over in one payload.
 *
 * <p>Unknown properties are ignored on purpose. AG-UI clients send fields this server does
 * not use yet, and a stricter reading would turn every protocol addition on the client
 * side into a 400 here.
 *
 * @param threadId       the conversation; the client owns it and reuses it across runs
 * @param runId          this turn; unique, and echoed on the lifecycle frames
 * @param messages       the whole thread, oldest first
 * @param tools          tools the <em>client</em> can execute, offered to the model this run
 * @param context        arbitrary context items the client wants the model to see
 * @param state          the client's mirror of agent state, used as the run's starting snapshot
 * @param forwardedProps anything else the client wants to pass through
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunAgentInput(
        String threadId,
        String runId,

        @NotEmpty(message = "At least one message is required")
        List<AgUiMessage> messages,

        List<ToolDefinition> tools,
        List<ContextItem> context,
        Map<String, Object> state,
        Map<String, Object> forwardedProps
) {

    /**
     * A tool the browser will execute. The name and JSON Schema go to the model verbatim;
     * a call to one of these is dispatched back to the client rather than run here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    }

    /** A labelled piece of context — what page the merchant is on, what they have selected. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContextItem(String description, String value) {
    }

    public RunAgentInput {
        threadId = threadId == null || threadId.isBlank() ? UUID.randomUUID().toString() : threadId;
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
        tools = tools == null ? List.of() : List.copyOf(tools);
        context = context == null ? List.of() : List.copyOf(context);
    }

    /**
     * The message the guardrails scan and the log records. The last user message is the
     * one that triggered this run — everything after it is the agent's own output.
     */
    public String latestUserMessage() {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgUiMessage message = messages.get(i);
            if (message != null && message.hasRole("user")) {
                return message.content();
            }
        }
        return null;
    }
}
