package com.mercala.agent.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Turns a Spring AI streaming call into a {@link ChatStreamEvent} flux with the tenant
 * context attached, shared by the merchant and shopper services so the part that is easy to
 * get subtly wrong cannot drift between them.
 *
 * <h2>Tenant context and threads</h2>
 *
 * <p>{@link AgentContext} is a {@link ThreadLocal} and it is what keeps every tool call
 * tenant-scoped. Reactive pipelines move work between threads, so this needs care.
 *
 * <p>The context is set as the first statement inside {@link Flux#defer}, which runs at
 * subscription time on the {@link Schedulers#boundedElastic()} worker chosen by
 * {@link Flux#subscribeOn}. The model call and its tool invocations then run on that same
 * worker, so tools see the right tenant.
 *
 * <p>Cleanup is bound to the <em>inner</em> chain rather than the outer one. An outer
 * {@code doFinally} sits downstream of {@code subscribeOn} and therefore runs on whichever
 * thread delivers the terminal signal — which on cancellation is the servlet request thread,
 * not the worker. That would clear the wrong ThreadLocal and leave the tenant context on a
 * pooled worker. SSE clients disconnect routinely, so that is a normal path, not an edge
 * case.
 *
 * <h2>What is and is not guaranteed</h2>
 *
 * <p>Guaranteed: an agent turn always sets its own context before the model call, so one
 * request can never observe another's tenant. That is the property that matters.
 *
 * <p>Not guaranteed: that the ThreadLocal is empty between turns. A cancellation racing the
 * inner cleanup can still leave a value on a pooled worker. It is harmless here because
 * every agent path overwrites it before use, but it means {@link AgentContext#current()}
 * cannot be relied on to throw for unrelated work that happens to run on
 * {@code boundedElastic}. Moving tool context onto the Reactor context instead of a
 * ThreadLocal would remove the caveat entirely — tracked in HAL-496.
 */
@Component
public class AgentStreamer {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamer.class);

    private final ChatModel chatModel;
    private final Duration streamTimeout;

    public AgentStreamer(
            ChatModel chatModel,
            @Value("${mercala.agent.stream-timeout:120s}") Duration streamTimeout) {
        this.chatModel = chatModel;
        this.streamTimeout = streamTimeout;
    }

    /**
     * @param prompt         the built prompt, including tool options
     * @param context        tenant context attached for the duration of the call
     * @param conversationId echoed on every frame so a client can correlate
     */
    public Flux<ChatStreamEvent> stream(Prompt prompt, AgentContext context, String conversationId) {
        String id = conversationId != null ? conversationId : UUID.randomUUID().toString();

        return Flux.defer(() -> {
                    // Runs on the boundedElastic worker that will also run the model call.
                    AgentContext.set(context);
                    log.info("Streaming agent turn — tenant={}, conversation={}", context.tenantId(), id);

                    return chatModel.stream(prompt)
                            // Without this a stalled upstream never terminates: the worker
                            // stays occupied, and boundedElastic is bounded, so enough
                            // stalled turns starve unrelated streaming requests.
                            .timeout(streamTimeout)
                            .flatMap(response -> Flux.fromIterable(toEvents(response, id)))
                            .concatWith(Flux.just(ChatStreamEvent.done(id)))
                            // Inner, not outer — see the class comment. Outer would clear the
                            // servlet thread's ThreadLocal on cancellation and leave the
                            // worker holding this tenant's context.
                            .doFinally(signal -> AgentContext.clear());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.error("Agent stream failed — conversation={}", id, error);
                    // A frame, not a failed stream: by the time tokens flow the 200 is
                    // already committed, so failing here would strand the client with a
                    // truncated body and no reason.
                    return Flux.just(ChatStreamEvent.error(
                            "The assistant could not complete this request.", id));
                });
    }

    private static List<ChatStreamEvent> toEvents(ChatResponse response, String conversationId) {
        List<ChatStreamEvent> events = new ArrayList<>();
        if (response == null || response.getResults() == null) {
            return events;
        }

        for (Generation generation : response.getResults()) {
            if (generation.getOutput() == null) {
                continue;
            }
            String chunk = generation.getOutput().getContent();
            // Spring AI emits empty-content frames around tool calls and at completion;
            // forwarding them would produce meaningless empty SSE events.
            if (chunk != null && !chunk.isEmpty()) {
                events.add(ChatStreamEvent.token(chunk, conversationId));
            }
        }
        return events;
    }
}
