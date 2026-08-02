package com.mercala.agent.agui;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message in the client-owned thread.
 *
 * <p>The thread lives in the browser, not on the server, and arrives whole on every run.
 * That is what makes the interesting flows possible at all: the agent asks a question,
 * the run ends, the merchant answers minutes later, and the next run replays the
 * conversation including the answer. A server-side session would have had to survive that
 * gap, and a reconnect, and a second tab.
 *
 * @param id         client-assigned, stable across runs
 * @param role       {@code user}, {@code assistant}, {@code system}, {@code tool}, {@code developer}
 * @param content    the text; null on an assistant message that was purely tool calls
 * @param name       tool name, on a {@code tool} message
 * @param toolCallId which call this {@code tool} message answers
 * @param toolCalls  the calls an assistant message made
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgUiMessage(
        String id,
        String role,
        String content,
        String name,
        String toolCallId,
        List<ToolCallRef> toolCalls
) {

    /** A tool call as it appears on an assistant message, mirroring the OpenAI shape. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallRef(String id, String type, FunctionRef function) {

        public record FunctionRef(String name, String arguments) {
        }
    }

    public static AgUiMessage user(String content) {
        return new AgUiMessage(null, "user", content, null, null, null);
    }

    public boolean hasRole(String candidate) {
        return role != null && role.equalsIgnoreCase(candidate);
    }
}
