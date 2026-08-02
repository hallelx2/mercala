package com.mercala.agent.chat;

import java.util.UUID;

/**
 * Holds per-request context that flows through the agent pipeline.
 * The tenantId identifies the merchant's store; the userId identifies the acting user.
 * Both are extracted from the authenticated session / JWT and passed to every tool invocation
 * so that the agent NEVER operates in god-mode — every action is tenant-scoped.
 */
public record AgentContext(
        UUID tenantId,
        UUID userId,
        String userRole
) {

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
