package com.mercala.agent.chat;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Turns a Spring AI streaming call into a {@link ChatStreamEvent} flux, with the tenant
 * context correctly attached.
 *
 * <p>Shared by the merchant and shopper services so the two cannot drift on the part that
 * is easy to get subtly wrong.
 *
 * <h2>Why the context handling looks like this</h2>
 *
 * <p>{@link AgentContext} is a {@link ThreadLocal}, and it is what keeps every tool call
 * tenant-scoped. Reactive pipelines are free to move work between threads, so a naive
 * {@code set()} before subscribing can leave the model's thread with no context at all —
 * and tool calls run on that thread.
 *
 * <p>Two things make this safe rather than merely likely-to-work:
 *
 * <ol>
 *   <li>The context is set <em>inside</em> {@link Flux#defer}, which runs at subscription
 *       time on the thread selected by {@link Flux#subscribeOn}. The model call and its tool
 *       invocations then happen on that same thread.</li>
 *   <li>{@link AgentContext#current()} throws when unset rather than returning a default. So
 *       if propagation ever does fail, the request fails loudly instead of executing a tool
 *       with no tenant — the failure mode is a broken request, never a cross-tenant read.</li>
 * </ol>
 *
 * <p>That second property is what makes this acceptable at all. Without it, a thread switch
 * would silently produce god-mode tool access.
 */
@Component
public class AgentStreamer {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamer.class);

    private final ChatModel chatModel;

    public AgentStreamer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * @param prompt         the built prompt, including tool options
     * @param context        tenant context to attach for the duration of the call
     * @param conversationId echoed on every frame so a client can correlate
     */
    public Flux<ChatStreamEvent> stream(Prompt prompt, AgentContext context, String conversationId) {
        String id = conversationId != null ? conversationId : UUID.randomUUID().toString();

        return Flux.defer(() -> {
                    // Runs on the subscribeOn thread — the same one the model call will use.
                    AgentContext.set(context);
                    log.info("Streaming agent turn — tenant={}, conversation={}", context.tenantId(), id);

                    return chatModel.stream(prompt)
                            .flatMap(response -> Flux.fromIterable(toEvents(response, id)))
                            .concatWith(Flux.just(ChatStreamEvent.done(id)));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> AgentContext.clear())
                .onErrorResume(error -> {
                    log.error("Agent stream failed — conversation={}", id, error);
                    // Deliberately a frame, not a failed stream: the 200 is already committed
                    // by the time tokens flow, so failing here would strand the client with a
                    // truncated body and no reason.
                    return Flux.just(ChatStreamEvent.error(
                            "The assistant could not complete this request.", id));
                });
    }

    private static java.util.List<ChatStreamEvent> toEvents(
            org.springframework.ai.chat.model.ChatResponse response, String conversationId) {

        java.util.List<ChatStreamEvent> events = new java.util.ArrayList<>();
        if (response == null || response.getResults() == null) {
            return events;
        }

        for (Generation generation : response.getResults()) {
            if (generation.getOutput() == null) {
                continue;
            }
            String chunk = generation.getOutput().getContent();
            // Spring AI emits empty content frames around tool calls and at completion;
            // forwarding them would produce meaningless empty SSE events.
            if (chunk != null && !chunk.isEmpty()) {
                events.add(ChatStreamEvent.token(chunk, conversationId));
            }
        }
        return events;
    }
}
