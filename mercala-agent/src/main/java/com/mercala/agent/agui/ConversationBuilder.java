package com.mercala.agent.agui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Turns the client's thread into the message list the model is given.
 *
 * <h2>Why history is rebuilt every run</h2>
 *
 * <p>The previous chat endpoint sent {@code [system, user]} and nothing else, so every turn
 * started from nothing: no follow-ups, no "make that one navy instead", no way for an
 * answer to relate to a question. Replaying the client's thread is what makes the agent
 * conversational, and it costs nothing in server state — there is no session to expire,
 * survive a redeploy, or diverge between two open tabs.
 *
 * <h2>Bounding</h2>
 *
 * <p>The thread is trimmed to the most recent {@link #MAX_MESSAGES} because it is
 * client-supplied and therefore unbounded: a long-running conversation would otherwise grow
 * the prompt until the model rejected it, and a hostile client could do that deliberately.
 * Trimming keeps the newest messages, which are the ones the current turn depends on.
 */
public final class ConversationBuilder {

    private static final Logger log = LoggerFactory.getLogger(ConversationBuilder.class);

    /**
     * Enough for a working session — the merchant's opening request, the agent's questions,
     * the answers, and several rounds of correction — without letting a client set the
     * prompt size.
     */
    static final int MAX_MESSAGES = 40;

    private ConversationBuilder() {
    }

    /**
     * @param systemPrompt the agent persona, always first and never taken from the client
     * @param thread       the client's messages, oldest first
     */
    public static List<Message> build(String systemPrompt, List<AgUiMessage> thread) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        if (thread == null || thread.isEmpty()) {
            return messages;
        }

        List<AgUiMessage> recent = thread.size() > MAX_MESSAGES
                ? dropOrphanedToolResults(thread.subList(thread.size() - MAX_MESSAGES, thread.size()))
                : thread;
        if (recent.size() < thread.size()) {
            log.info("Trimmed conversation from {} to the most recent {} messages", thread.size(), recent.size());
        }

        for (AgUiMessage message : recent) {
            Message converted = convert(message);
            if (converted != null) {
                messages.add(converted);
            }
        }
        return messages;
    }

    /**
     * Drops {@code tool} messages left at the front of a trimmed window with nothing to
     * answer.
     *
     * <p>A tool result is only meaningful directly after the assistant message that made
     * the call. Trimming can cut between the two, and an OpenAI-compatible API rejects the
     * remainder outright — the whole turn fails with a 400 rather than degrading. Since the
     * window is the newest messages, the orphans are always at the start.
     */
    private static List<AgUiMessage> dropOrphanedToolResults(List<AgUiMessage> window) {
        int start = 0;
        while (start < window.size() && window.get(start).hasRole("tool")) {
            start++;
        }
        if (start > 0) {
            log.info("Dropped {} tool result(s) whose call was trimmed out of the window", start);
        }
        return window.subList(start, window.size());
    }

    private static Message convert(AgUiMessage message) {
        if (message == null || message.role() == null) {
            return null;
        }

        return switch (message.role().toLowerCase()) {
            case "user" -> new UserMessage(text(message));
            case "assistant" -> assistant(message);
            case "tool" -> tool(message);
            // A client-side system message would let the browser rewrite the agent's
            // instructions, so it is folded in as context rather than authority.
            case "system", "developer" -> new UserMessage("Context: " + text(message));
            // Activity messages are the client's own rendering of what happened. The model
            // already knows — it is what produced them.
            default -> null;
        };
    }

    private static Message assistant(AgUiMessage message) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        if (message.toolCalls() != null) {
            for (AgUiMessage.ToolCallRef ref : message.toolCalls()) {
                if (ref == null || ref.function() == null) {
                    continue;
                }
                toolCalls.add(new AssistantMessage.ToolCall(
                        ref.id(),
                        ref.type() == null ? "function" : ref.type(),
                        ref.function().name(),
                        ref.function().arguments() == null ? "{}" : ref.function().arguments()));
            }
        }
        return new AssistantMessage(text(message), Map.of(), toolCalls);
    }

    /**
     * A tool message is how an answer gets back in — the merchant's choice, the browser's
     * result. Without the {@code toolCallId} the model cannot tell which of its questions
     * was answered, so a message missing one is dropped rather than being attached to the
     * wrong call.
     */
    private static Message tool(AgUiMessage message) {
        if (message.toolCallId() == null || message.toolCallId().isBlank()) {
            log.warn("Dropping tool message with no toolCallId — it cannot be matched to a call");
            return null;
        }
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(
                message.toolCallId(),
                message.name() == null ? "" : message.name(),
                text(message))));
    }

    private static String text(AgUiMessage message) {
        return message.content() == null ? "" : message.content();
    }
}
