package com.mercala.agent.agui;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One frame of an <a href="https://github.com/ag-ui-protocol/ag-ui">AG-UI</a> run.
 *
 * <p>The protocol is deliberately not "tokens plus a done marker". A merchant turn spends
 * most of its wall-clock inside tools — creating a product, asking image generation for a
 * render — during which the model emits nothing. A stream carrying only text therefore
 * reproduces the silence streaming was meant to remove. AG-UI names each thing that is
 * happening, so the client can render the run rather than guess at it.
 *
 * <p><strong>No global timestamp.</strong> The spec makes {@code timestamp} optional, and a
 * server clock is the wrong instrument for the one thing a UI actually wants to time: how
 * long the merchant waited. Arrival time at the client measures that correctly. Where the
 * server does know something the client cannot — how long a tool itself took, excluding
 * transport — it is carried explicitly on {@link ToolCallResult#durationMs()}.
 *
 * <p>Serialisation is flat with a {@code type} discriminator, matching the wire format the
 * AG-UI clients expect. Null fields are omitted so an optional field that was never set
 * does not show up as an explicit null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface AgUiEvent {

    /** The discriminator every AG-UI client switches on. */
    @JsonProperty("type")
    String type();

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Mandatory opening frame. Exactly one per run. */
    record RunStarted(String threadId, String runId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RUN_STARTED;
        }
    }

    /** Terminal success frame. Mutually exclusive with {@link RunError}. */
    record RunFinished(String threadId, String runId, Map<String, Object> result) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RUN_FINISHED;
        }
    }

    /**
     * Terminal failure frame. Delivered as a frame rather than by failing the HTTP
     * response: once SSE has begun the 200 is committed, so aborting would leave the
     * client with a truncated body and no explanation.
     */
    record RunError(String threadId, String runId, String message, String code) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RUN_ERROR;
        }
    }

    /** Optional progress marker — one model turn, one tool phase, one retry. */
    record StepStarted(String stepName) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STEP_STARTED;
        }
    }

    record StepFinished(String stepName) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STEP_FINISHED;
        }
    }

    // ── Text ───────────────────────────────────────────────────────────────

    record TextMessageStart(String messageId, String role) implements AgUiEvent {
        public TextMessageStart(String messageId) {
            this(messageId, "assistant");
        }

        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_START;
        }
    }

    record TextMessageContent(String messageId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_CONTENT;
        }
    }

    record TextMessageEnd(String messageId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TEXT_MESSAGE_END;
        }
    }

    // ── Tool calls ─────────────────────────────────────────────────────────

    record ToolCallStart(String toolCallId, String toolCallName, String parentMessageId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_START;
        }
    }

    /** Arguments as a JSON fragment. May arrive in one frame or several. */
    record ToolCallArgs(String toolCallId, String delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_ARGS;
        }
    }

    record ToolCallEnd(String toolCallId) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_END;
        }
    }

    /**
     * What the tool actually returned. {@code error} is true when the tool threw — the
     * run continues, because a failed tool is information the model and the merchant both
     * need, not a reason to tear down the stream.
     */
    record ToolCallResult(
            String messageId,
            String toolCallId,
            String content,
            String role,
            Long durationMs,
            Boolean error
    ) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.TOOL_CALL_RESULT;
        }
    }

    // ── State ──────────────────────────────────────────────────────────────

    /** Complete replacement of the agent state the client mirrors. */
    record StateSnapshot(Map<String, Object> snapshot) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STATE_SNAPSHOT;
        }
    }

    /** RFC 6902 JSON Patch against the last snapshot. */
    record StateDelta(List<Map<String, Object>> delta) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.STATE_DELTA;
        }
    }

    /** Whole-conversation replacement, for a client that has drifted or just connected. */
    record MessagesSnapshot(List<AgUiMessage> messages) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.MESSAGES_SNAPSHOT;
        }
    }

    // ── Escape hatches ─────────────────────────────────────────────────────

    /**
     * Application-specific payload. Mercala uses it for the things that are ours rather
     * than the protocol's — a tool call being handed to the browser, an image enhancement
     * moving between phases.
     */
    record Custom(String name, Map<String, Object> value) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.CUSTOM;
        }
    }

    /** Passthrough of an upstream system's own event, for debugging. */
    record Raw(Map<String, Object> event, String source) implements AgUiEvent {
        @Override
        public String type() {
            return AgUiEventType.RAW;
        }
    }
}
