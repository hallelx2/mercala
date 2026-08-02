package com.mercala.agent.chat;

import java.util.UUID;

import com.mercala.agent.agui.AgentRunChannel;

/**
 * Holds per-request context that flows through the agent pipeline.
 * The tenantId identifies the merchant's store; the userId identifies the acting user.
 * Both are extracted from the authenticated session / JWT and passed to every tool invocation
 * so that the agent NEVER operates in god-mode — every action is tenant-scoped.
 *
 * <p>It also carries the run's {@link AgentRunChannel}. That is not an unrelated passenger:
 * the context is already the one thing guaranteed to reach a tool callback on whatever
 * thread Spring AI runs it on (HAL-515), so it is the only place the channel can live
 * without inventing a second propagation mechanism that would have to be kept correct
 * alongside this one.
 *
 * @param runChannel where tools report what they are doing; never null — use
 *                   {@link AgentRunChannel#inactive()} when nothing is listening
 */
public record AgentContext(
        UUID tenantId,
        UUID userId,
        String userRole,
        AgentRunChannel runChannel
) {

    /** Context for a path with no client listening — the blocking {@code /chat} endpoint, tests. */
    public AgentContext(UUID tenantId, UUID userId, String userRole) {
        this(tenantId, userId, userRole, AgentRunChannel.inactive());
    }

    public AgentContext {
        if (runChannel == null) {
            runChannel = AgentRunChannel.inactive();
        }
    }

    /** The same identity, reporting into a different run. */
    public AgentContext withChannel(AgentRunChannel channel) {
        return new AgentContext(tenantId, userId, userRole, channel);
    }

    /**
     * The current run's channel, or an inactive one when there is no context at all. Tools
     * call this rather than {@link #current()} so that a tool exercised outside a run — a
     * unit test, the blocking path — reports into a sink instead of throwing.
     */
    public static AgentRunChannel currentChannel() {
        AgentContext ctx = CURRENT.get();
        return ctx == null ? AgentRunChannel.inactive() : ctx.runChannel();
    }

    /**
     * ThreadLocal carrier so tool functions (which have no request scope)
     * can access the current tenant without explicit parameter passing.
     */
    private static final ThreadLocal<AgentContext> CURRENT = new ThreadLocal<>();

    public static void set(AgentContext ctx) {
        CURRENT.set(ctx);
    }

    public static AgentContext current() {
        AgentContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException("AgentContext not set — call AgentContext.set() before invoking agent tools");
        }
        return ctx;
    }

    /**
     * Non-throwing read for infrastructure that must observe absence rather than fail on
     * it — the context-propagation {@link AgentContextAccessor} snapshots ThreadLocals on
     * arbitrary threads where no context legitimately exists yet.
     */
    public static AgentContext currentOrNull() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
