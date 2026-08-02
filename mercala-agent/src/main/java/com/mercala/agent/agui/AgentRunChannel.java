package com.mercala.agent.agui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The side channel a run's tools emit AG-UI events into.
 *
 * <h2>Why tools emit rather than the stream inferring</h2>
 *
 * <p>Spring AI executes tool calls inside the model client, so the frames coming back out
 * of {@code chatModel.stream(...)} describe what the model <em>asked for</em>, not what
 * happened. Scraping them would give the UI a tool name and nothing else — no result, no
 * failure, no duration, and a confident "created product" for a call that threw. Emitting
 * from inside the tool, where the outcome is known, is the only way the client can be told
 * the truth.
 *
 * <p>The channel reaches the tools by riding on {@link com.mercala.agent.chat.AgentContext},
 * which already crosses the scheduler hop via {@code AgentContextAccessor} (HAL-515). No
 * second propagation mechanism is introduced.
 *
 * <h2>Threading</h2>
 *
 * <p>Parallel tool calls emit concurrently, and a Reactor unicast sink is not safe against
 * that, so emission is serialised on this object's monitor. The critical section is a
 * buffer append — it never blocks on anything a tool is doing.
 *
 * <p>Emission after {@link #close()} is dropped rather than retried. The alternative is a
 * busy-loop against a terminated sink, and a tool that outlives its own run — a cancelled
 * SSE connection is the ordinary case here — would spin a worker forever.
 */
public final class AgentRunChannel {

    private static final Logger log = LoggerFactory.getLogger(AgentRunChannel.class);

    /** JSON Patch path for the per-tool-call activity map the client mirrors. */
    private static final String ACTIVITY_PATH = "/activity/";

    private final Sinks.Many<AgUiEvent> sink;
    private final AtomicInteger toolCallCounter = new AtomicInteger();
    private final boolean active;
    private final String runId;
    private boolean closed;

    private AgentRunChannel(boolean active, String runId) {
        this.active = active;
        this.runId = runId;
        this.sink = active ? Sinks.many().unicast().onBackpressureBuffer() : null;
    }

    /** A channel whose events will be streamed to a client. */
    public static AgentRunChannel active(String runId) {
        return new AgentRunChannel(true, runId);
    }

    /**
     * A channel that swallows everything, for the blocking {@code /chat} path and for
     * tests. Tools call the same code either way — there is no "are we streaming?" branch
     * inside a tool, which is exactly the branch that rots.
     */
    public static AgentRunChannel inactive() {
        return new AgentRunChannel(false, null);
    }

    public boolean isActive() {
        return active;
    }

    /** Events emitted by tools during this run. Cold until subscribed; buffered until then. */
    public Flux<AgUiEvent> events() {
        return active ? sink.asFlux() : Flux.empty();
    }

    /** Tool call ids are per-run and monotonic so a client can order them without a clock. */
    public String nextToolCallId() {
        String base = runId != null ? runId : UUID.randomUUID().toString();
        return "tc_" + base + "_" + toolCallCounter.incrementAndGet();
    }

    public void emit(AgUiEvent event) {
        if (!active || event == null) {
            return;
        }
        synchronized (this) {
            if (closed) {
                log.debug("Dropping {} emitted after the run closed", event.type());
                return;
            }
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                log.warn("Could not emit {} on run {}: {}", event.type(), runId, result);
            }
        }
    }

    /**
     * The opening half of a tool call: the protocol lifecycle plus a state delta that puts
     * the call into the client's activity map. The delta is what lets a client render a
     * live "what is it doing" panel without reconstructing it from the event log.
     */
    public void toolStarted(String toolCallId, String toolName, String argumentsJson) {
        emit(new AgUiEvent.ToolCallStart(toolCallId, toolName, null));
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            emit(new AgUiEvent.ToolCallArgs(toolCallId, argumentsJson));
        }
        emit(new AgUiEvent.ToolCallEnd(toolCallId));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", toolName);
        entry.put("status", "running");
        emit(new AgUiEvent.StateDelta(List.of(patch("add", ACTIVITY_PATH + toolCallId, entry))));
        emit(new AgUiEvent.StateDelta(List.of(patch("replace", "/phase", "working"))));
    }

    /** The closing half: the result the model will see, and what it cost. */
    public void toolFinished(String toolCallId, String content, long durationMs, boolean error) {
        emit(new AgUiEvent.ToolCallResult(
                "tr_" + toolCallId, toolCallId, content, "tool", durationMs, error ? Boolean.TRUE : null));

        List<Map<String, Object>> delta = new ArrayList<>();
        delta.add(patch("replace", ACTIVITY_PATH + toolCallId + "/status", error ? "failed" : "done"));
        delta.add(patch("add", ACTIVITY_PATH + toolCallId + "/durationMs", durationMs));
        emit(new AgUiEvent.StateDelta(delta));
        emit(new AgUiEvent.StateDelta(List.of(patch("replace", "/phase", "thinking"))));
    }

    public void custom(String name, Map<String, Object> value) {
        emit(new AgUiEvent.Custom(name, value));
    }

    /**
     * Ends the event stream. Called when the model turn completes, succeeds or fails —
     * without it the merged stream would never terminate and the SSE response would hang
     * open until the proxy timed it out.
     */
    public void close() {
        if (!active) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            sink.tryEmitComplete();
        }
    }

    private static Map<String, Object> patch(String op, String path, Object value) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", op);
        patch.put("path", path);
        patch.put("value", value);
        return patch;
    }
}
