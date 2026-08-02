package com.mercala.agent.chat;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Teaches the context-propagation library how to carry {@link AgentContext} across thread
 * hops inside reactive pipelines.
 *
 * <p>This exists because Spring AI does not run tool callbacks on the thread that started
 * the stream. {@link AgentStreamer} sets the ThreadLocal on the {@code boundedElastic}
 * worker that subscribes to the model call, but the model's own flux moves tool execution
 * onto other threads — where the ThreadLocal is empty, {@code MercalaCoreClient} omits the
 * tenant header, and core answers 401 (HAL-515). With this accessor registered and
 * {@code Hooks.enableAutomaticContextPropagation()} on, Reactor restores the ThreadLocal
 * around every signal of any pipeline whose Reactor {@code Context} carries {@link #KEY}
 * — including the operators inside Spring AI, because a Reactor context propagates
 * upstream from the subscriber through the whole chain.
 */
public final class AgentContextAccessor implements ThreadLocalAccessor<AgentContext> {

    /** The Reactor Context key {@link AgentStreamer} writes and this accessor restores from. */
    public static final String KEY = "mercala.agent-context";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public AgentContext getValue() {
        return AgentContext.currentOrNull();
    }

    @Override
    public void setValue(AgentContext value) {
        AgentContext.set(value);
    }

    @Override
    public void setValue() {
        AgentContext.clear();
    }
}
