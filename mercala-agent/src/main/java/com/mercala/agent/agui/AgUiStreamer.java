package com.mercala.agent.agui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.chat.AgentContextAccessor;

import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import reactor.core.scheduler.Schedulers;

/**
 * Runs one agent turn and narrates it as AG-UI events.
 *
 * <h2>Two sources, one stream</h2>
 *
 * <p>A turn produces events from two places at once. The model produces text, on the
 * reactive chain returned by {@code chatModel.stream(...)}. The tools produce their own
 * lifecycle, from inside the model client, on threads this class never sees — they arrive
 * through {@link AgentRunChannel}. The two are merged, which is why a tool call can appear
 * between two sentences instead of after all of them.
 *
 * <p>The channel is closed when the model turn terminates, for any reason. Without that the
 * merged stream has no reason to complete and the SSE response stays open until a proxy
 * gives up on it.
 *
 * <h2>Tenant context</h2>
 *
 * <p>Identical to {@link com.mercala.agent.chat.AgentStreamer} and for the same reasons:
 * the ThreadLocal is set inside {@code defer} so it lands on the subscribing worker, cleanup
 * is bound to the inner chain so a cancelled SSE connection does not clear the servlet
 * thread's context instead of the worker's, and the Reactor context carries the value so
 * Spring AI's tool callbacks see this turn's tenant on whatever thread runs them (HAL-515).
 *
 * <h2>Exactly one terminal frame</h2>
 *
 * <p>A run ends in {@code RUN_FINISHED} or {@code RUN_ERROR} and never both, never neither.
 * Clients key their "is it still working" state on that, so a run that ends silently leaves
 * a spinner turning forever.
 */
@Component
public class AgUiStreamer {

    private static final Logger log = LoggerFactory.getLogger(AgUiStreamer.class);

    private static final String STEP_MODEL_TURN = "model-turn";

    private final ChatModel chatModel;
    private final Duration streamTimeout;
    private final int connectRetries;
    private final Duration retryBackoff;

    public AgUiStreamer(
            ChatModel chatModel,
            @Value("${mercala.agent.stream-timeout:120s}") Duration streamTimeout,
            @Value("${mercala.agent.connect-retries:2}") int connectRetries,
            @Value("${mercala.agent.retry-backoff:2s}") Duration retryBackoff) {
        this.chatModel = chatModel;
        this.streamTimeout = streamTimeout;
        this.connectRetries = connectRetries;
        this.retryBackoff = retryBackoff;
    }

    /**
     * Whether a failure is the kind a second attempt could get past: the connection never
     * came up, or it died before the provider said anything. A refusal — a 4xx, a bad key,
     * a rejected request — is not, and retrying one only spends the merchant's time.
     *
     * <p>Nor is a stall: {@code stream-timeout} sits below the servlet and nginx timeouts
     * on purpose, and retrying a turn that has already burned it would run past both and
     * turn a clean timeout frame back into a dead connection.
     */
    private static boolean isWorthRetrying(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.client.HttpClientErrorException
                    || cause instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                return false;
            }
            if (cause instanceof java.io.IOException
                    || cause instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                    || cause instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param prompt   the built prompt, including tool options
     * @param context  tenant identity for the turn; the channel is attached here
     * @param channel  where the turn's tools report
     * @param threadId the client's conversation id, echoed on lifecycle frames
     * @param runId    this turn's id, echoed on lifecycle frames
     */
    public Flux<AgUiEvent> stream(
            Prompt prompt,
            AgentContext context,
            AgentRunChannel channel,
            String threadId,
            String runId) {

        AgentContext runContext = context.withChannel(channel);
        String messageId = "msg_" + UUID.randomUUID();
        AtomicBoolean textStarted = new AtomicBoolean(false);
        AtomicBoolean answered = new AtomicBoolean(false);

        Flux<AgUiEvent> modelTurn = Flux.defer(() -> {
                    // Runs on the boundedElastic worker that will also run the model call.
                    AgentContext.set(runContext);
                    log.info("AG-UI run starting — tenant={}, thread={}, run={}",
                            runContext.tenantId(), threadId, runId);

                    // Deferred so a retry issues a fresh call rather than resubscribing to
                    // the connection that just failed.
                    return Flux.defer(() -> chatModel.stream(prompt))
                            // A stalled upstream would otherwise hold a bounded worker
                            // indefinitely, and enough of them starve unrelated streams.
                            .timeout(streamTimeout)
                            .doOnNext(response -> answered.set(true))
                            // A connection that never produced a chunk is worth trying
                            // again: the provider may be a long way away, and a reset
                            // during the handshake currently costs the merchant their whole
                            // turn for no reason they can see.
                            //
                            // Only before the first chunk, which is the load-bearing part.
                            // Past that point the model may already have called a tool —
                            // created a product, adjusted stock — and a retry would do it
                            // twice. Nothing about a stream is safe to replay once it has
                            // started; everything about it is safe to replay before.
                            .retryWhen(Retry.backoff(connectRetries, retryBackoff)
                                    .filter(error -> !answered.get() && isWorthRetrying(error))
                                    .doBeforeRetry(signal -> log.warn(
                                            "Model call failed before answering ({}); retrying — thread={}, run={}",
                                            signal.failure().toString(), threadId, runId)))
                            // Retry wraps the cause in a RetryExhaustedException, which
                            // would reach the frame mapper as an unrecognised error and
                            // cost a stream timeout its own code.
                            .onErrorMap(
                                    reactor.core.Exceptions::isRetryExhausted,
                                    error -> error.getCause() == null ? error : error.getCause())
                            .flatMap(response -> Flux.fromIterable(textEvents(response, messageId, textStarted)))
                            .concatWith(Flux.defer(() -> textStarted.get()
                                    ? Flux.just(new AgUiEvent.TextMessageEnd(messageId))
                                    : Flux.empty()))
                            // Inner, not outer: an outer doFinally sits downstream of
                            // subscribeOn and would run on the servlet thread when the
                            // client disconnects, clearing the wrong ThreadLocal.
                            .doFinally(signal -> {
                                AgentContext.clear();
                                channel.close();
                            });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(ctx -> ctx.put(AgentContextAccessor.KEY, runContext));

        Flux<AgUiEvent> turn = Flux.merge(modelTurn, channel.events());

        return Flux.concat(
                        Flux.just(
                                new AgUiEvent.RunStarted(threadId, runId),
                                new AgUiEvent.StateSnapshot(initialState(runContext, threadId, runId)),
                                new AgUiEvent.StepStarted(STEP_MODEL_TURN)),
                        turn,
                        Flux.just(
                                new AgUiEvent.StepFinished(STEP_MODEL_TURN),
                                new AgUiEvent.RunFinished(threadId, runId, Map.of("status", "completed"))))
                .onErrorResume(error -> {
                    log.error("AG-UI run failed — thread={}, run={}", threadId, runId, error);
                    return Flux.just(new AgUiEvent.RunError(
                            threadId, runId, describe(error), classify(error)));
                });
    }

    /**
     * What the client mirrors from the first frame onward. {@code activity} is filled in by
     * {@link AgentRunChannel} as tools run, so a client can render a live panel by applying
     * patches to this rather than replaying the whole event log.
     */
    private static Map<String, Object> initialState(AgentContext context, String threadId, String runId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "thinking");
        state.put("threadId", threadId);
        state.put("runId", runId);
        state.put("role", context.userRole());
        state.put("activity", new LinkedHashMap<String, Object>());
        return state;
    }

    /**
     * Text frames, plus the one state change worth reporting from here: the moment the model
     * stops deliberating and starts answering.
     */
    private List<AgUiEvent> textEvents(ChatResponse response, String messageId, AtomicBoolean textStarted) {
        List<AgUiEvent> events = new ArrayList<>();
        if (response == null || response.getResults() == null) {
            return events;
        }

        for (Generation generation : response.getResults()) {
            if (generation.getOutput() == null) {
                continue;
            }
            String chunk = generation.getOutput().getContent();
            // Spring AI emits empty-content frames around tool calls and at completion.
            // Forwarding them would produce empty deltas the client has to filter anyway.
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            if (textStarted.compareAndSet(false, true)) {
                events.add(new AgUiEvent.TextMessageStart(messageId));
                events.add(new AgUiEvent.StateDelta(List.of(
                        Map.of("op", "replace", "path", "/phase", "value", "responding"))));
            }
            events.add(new AgUiEvent.TextMessageContent(messageId, chunk));
        }
        return events;
    }

    /**
     * What the merchant reads. Model and provider exceptions carry vendor detail and
     * sometimes prompt fragments, so they are logged rather than forwarded — with the
     * exception of a timeout, where knowing it was slow rather than broken changes whether
     * retrying is sensible.
     */
    private static String describe(Throwable error) {
        if (error instanceof TimeoutException) {
            return "The assistant took too long to respond. Try again, or break the request into smaller steps.";
        }
        return "The assistant could not complete this request.";
    }

    private static String classify(Throwable error) {
        if (error instanceof TimeoutException) {
            return "STREAM_TIMEOUT";
        }
        if (error instanceof SecurityException) {
            return "GUARDRAIL_REJECTED";
        }
        return "AGENT_ERROR";
    }
}
