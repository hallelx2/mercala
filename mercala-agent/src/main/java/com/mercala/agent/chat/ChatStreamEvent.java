package com.mercala.agent.chat;

import java.time.Instant;
import java.util.Map;

/**
 * One frame of a streamed agent turn.
 *
 * <p>Typed rather than raw text because a chat turn is not only tokens. Tool calls are the
 * slowest part — creating a product, generating an image — and during them the model emits
 * nothing at all. Streaming only tokens would therefore reproduce the exact silence
 * streaming was meant to remove, just later in the turn. A distinct {@code TOOL} frame lets
 * the UI say "creating product…" instead of showing a stalled cursor.
 *
 * @param type    what this frame represents
 * @param content token text for {@code TOKEN}, tool name for {@code TOOL}, message for {@code ERROR}
 * @param toolArguments arguments for a {@code TOOL} frame, otherwise null
 * @param conversationId stable for the whole turn so a client can correlate frames
 * @param timestamp when the frame was produced
 */
public record ChatStreamEvent(
        Type type,
        String content,
        Map<String, Object> toolArguments,
        String conversationId,
        Instant timestamp
) {

    public enum Type {
        /** An incremental piece of the assistant's reply. */
        TOKEN,
        /** The agent invoked a tool; content carries the tool name. */
        TOOL,
        /** Terminal success frame. No further frames follow. */
        DONE,
        /** Terminal failure frame. No further frames follow. */
        ERROR
    }

    public static ChatStreamEvent token(String text, String conversationId) {
        return new ChatStreamEvent(Type.TOKEN, text, null, conversationId, Instant.now());
    }

    public static ChatStreamEvent tool(String toolName, Map<String, Object> arguments, String conversationId) {
        return new ChatStreamEvent(Type.TOOL, toolName, arguments, conversationId, Instant.now());
    }

    public static ChatStreamEvent done(String conversationId) {
        return new ChatStreamEvent(Type.DONE, null, null, conversationId, Instant.now());
    }

    /**
     * Errors are delivered as a frame rather than by failing the stream. Once SSE has begun,
     * the response status is already committed as 200, so aborting mid-stream would leave a
     * browser client with a truncated body and no explanation. A terminal ERROR frame is
     * something the UI can actually render.
     */
    public static ChatStreamEvent error(String message, String conversationId) {
        return new ChatStreamEvent(Type.ERROR, message, null, conversationId, Instant.now());
    }
}
