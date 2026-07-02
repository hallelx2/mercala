package com.mercala.agent.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbound response from the agent to the merchant's chat interface.
 */
public record ChatResponse(
        String message,
        List<ToolInvocation> toolsUsed,
        String conversationId,
        Instant timestamp
) {

    /**
     * A record of a tool that the agent invoked during this turn.
     */
    public record ToolInvocation(
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> result
    ) {}
}
