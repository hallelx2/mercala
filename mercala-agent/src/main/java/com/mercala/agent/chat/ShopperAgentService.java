package com.mercala.agent.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * Conversational shopper discovery agent.
 *
 * A shopper types natural-language intent (e.g. "a gift under $50 for a hiker")
 * and the agent uses the hybrid search tool to find matching products, then returns
 * grounded, product-citation-backed answers — never hallucinated recommendations.
 *
 * Key differences from the Merchant agent:
 *   - READ-ONLY: only searchCatalog and getProduct tools (no createProduct, no updateInventory)
 *   - SHOPPER persona: friendly, recommendation-oriented
 *   - Grounded: every recommendation must reference real search results
 */
@Service
public class ShopperAgentService {

    private static final Logger log = LoggerFactory.getLogger(ShopperAgentService.class);

    private static final String SYSTEM_PROMPT = """
            You are the Mercala shopping assistant — a friendly, knowledgeable AI that helps
            shoppers discover products they'll love.

            ## Your capabilities:
            - **searchCatalog**: Search the product catalog by keyword or natural-language intent.
              Supports modes: 'hybrid' (default, best quality), 'semantic' (intent-based), 'lexical' (exact keyword).
            - **getProduct**: Look up detailed information about a specific product by its ID.

            ## Rules:
            1. **Grounded answers only**: Every product you recommend MUST come from a searchCatalog or getProduct result.
               Never invent product names, prices, or details. If search returns no results, say so honestly.
            2. When recommending products, always include:
               - Product name
               - Price
               - A brief reason why it matches the shopper's request
            3. If the shopper's query is vague, use 'hybrid' search mode first. If they ask for something very specific,
               use 'lexical' mode.
            4. Offer to show more details (variants, descriptions) if the shopper is interested in a product.
            5. Be conversational and helpful — you're a personal shopping assistant, not a search engine.
            6. If a product is out of stock or has limited availability, mention it.
            7. You are tenant-scoped — you can only show products from the current store.

            ## CRITICAL: Function Calling Format
            If you decide to invoke a function, format it exactly as:
            <function=toolName>{"arg1": "val1"}</function>
            Never include parentheses around the JSON arguments (e.g., never output <function=toolName(...)>).
            """;

    /**
     * Shopper only gets read-only tools — no createProduct or updateInventory.
     */
    private static final Set<String> SHOPPER_TOOLS = Set.of(
            "searchCatalog", "getProduct"
    );

    private final ChatModel chatModel;
    private final AgentStreamer agentStreamer;

    public ShopperAgentService(ChatModel chatModel, AgentStreamer agentStreamer) {
        this.chatModel = chatModel;
        this.agentStreamer = agentStreamer;
    }

    /**
     * Process a shopper's discovery query through the agent pipeline.
     */
    public com.mercala.agent.chat.ChatResponse chat(ChatRequest request) {
        UUID tenantId = request.tenantId();
        UUID userId = request.userId();

        try {
            AgentContext ctx = AgentContext.current();
            if (tenantId != null && !tenantId.equals(ctx.tenantId())) {
                throw new IllegalArgumentException("Tenant ID mismatch with authenticated session");
            }
            if (userId != null && !userId.equals(ctx.userId())) {
                throw new IllegalArgumentException("User ID mismatch with authenticated session");
            }
            // Use context values if request fields were omitted
            if (tenantId == null) tenantId = ctx.tenantId();
            if (userId == null) userId = ctx.userId();
        } catch (IllegalStateException ignored) {
            // No pre-existing context (e.g. from unit tests), proceed with request parameters
        }

        log.info("Shopper agent chat — tenant={}, user={}, message='{}'",
                tenantId, userId, truncate(request.message(), 80));

        // 1. Set tenant context — SHOPPER role (read-only)
        AgentContext.set(new AgentContext(tenantId, userId, "SHOPPER"));

        try {
            // 2. Build messages
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.add(new UserMessage(request.message()));

            // 3. Build options with read-only function calling
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withFunctions(SHOPPER_TOOLS)
                    .build();

            Prompt prompt = new Prompt(messages, options);

            // 4. Call the model
            ChatResponse aiResponse = chatModel.call(prompt);

            // 5. Extract the reply
            String reply = "";
            if (aiResponse != null && aiResponse.getResults() != null && !aiResponse.getResults().isEmpty()) {
                Generation generation = aiResponse.getResults().get(0);
                if (generation.getOutput() != null) {
                    reply = generation.getOutput().getContent();
                }
            }

            log.info("Shopper agent response generated — length={}", reply.length());

            return new com.mercala.agent.chat.ChatResponse(
                    reply,
                    List.of(),
                    request.conversationId() != null ? request.conversationId() : UUID.randomUUID().toString(),
                    Instant.now()
            );

        } finally {
            AgentContext.clear();
        }
    }


    /**
     * Streaming variant of {@link #chat}. Same prompt, same tools, same tenant guard —
     * the difference is that the reply arrives incrementally instead of after the whole
     * turn completes.
     *
     * <p>Kept alongside the blocking method rather than replacing it: the SDK's simple path
     * and any non-browser caller still want one response object.
     */
    public reactor.core.publisher.Flux<ChatStreamEvent> chatStream(ChatRequest request) {
        Resolved resolved = resolveIdentity(request);

        java.util.List<Message> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(request.message()));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withFunctions(SHOPPER_TOOLS)
                .build();

        return agentStreamer.stream(
                new Prompt(messages, options),
                new AgentContext(resolved.tenantId(), resolved.userId(), "SHOPPER"),
                request.conversationId());
    }

    /** Tenant/user reconciliation shared by the blocking and streaming paths. */
    private Resolved resolveIdentity(ChatRequest request) {
        UUID tenantId = request.tenantId();
        UUID userId = request.userId();
        try {
            AgentContext ctx = AgentContext.current();
            if (tenantId != null && !tenantId.equals(ctx.tenantId())) {
                throw new IllegalArgumentException("Tenant ID mismatch with authenticated session");
            }
            if (userId != null && !userId.equals(ctx.userId())) {
                throw new IllegalArgumentException("User ID mismatch with authenticated session");
            }
            if (tenantId == null) tenantId = ctx.tenantId();
            if (userId == null) userId = ctx.userId();
        } catch (IllegalStateException ignored) {
            // No ambient context (unit tests); fall back to the request values.
        }
        return new Resolved(tenantId, userId);
    }

    private record Resolved(UUID tenantId, UUID userId) {}

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
