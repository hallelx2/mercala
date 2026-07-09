package com.mercala.agent.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Orchestrates the merchant chat-to-action flow.
 * 
 * The merchant types a natural-language instruction (e.g., "add a navy linen shirt,
 * sizes S–L, $49") and this service:
 *   1. Sets the AgentContext (tenant-scoped, merchant-role-only).
 *   2. Builds a system prompt with the merchant persona + available tools.
 *   3. Calls the ChatModel with function-calling enabled.
 *   4. Collects tool invocations for the audit trail.
 *   5. Returns a ChatResponse with the agent's reply + tools used.
 */
@Service
public class MerchantAgentService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAgentService.class);

    private static final String SYSTEM_PROMPT = """
            You are the Mercala merchant assistant — a helpful, concise AI that helps store owners
            manage their product catalog and inventory.

            ## Your capabilities:
            - **createProduct**: Add a new product to the catalog with name, description, price, tags, and variants (SKU, price, attributes).
            - **getProduct**: Look up a product by its ID.
            - **searchCatalog**: Search the catalog by keyword or natural-language query (hybrid, semantic, or lexical modes).
            - **updateInventory**: Adjust stock levels for a product variant (add or remove units).
            - **requestProductImage**: Request asynchronous AI image generation for a product by providing details/prompt.

            ## Rules:
            1. Always confirm what you're about to do before calling a tool, unless the user's intent is unambiguous.
            2. When creating a product with variants, generate reasonable SKUs if the user doesn't provide them (e.g., "LINEN-SHIRT-NAVY-S").
            3. Prices must be positive numbers. If the user says "$49", use 49.00.
            4. After a successful action, summarize what was done (product name, ID, variant count, etc.).
            5. If you're unsure about a field, ask — don't guess.
            6. You are tenant-scoped. You can only act on the current merchant's store.

            ## CRITICAL: Function Calling Format
            If you decide to invoke a function, format it exactly as:
            <function=toolName>{"arg1": "val1"}</function>
            Never include parentheses around the JSON arguments (e.g., never output <function=toolName(...)>).
            """;

    private static final Set<String> MERCHANT_TOOLS = Set.of(
            "createProduct", "getProduct", "searchCatalog", "updateInventory", "requestProductImage"
    );

    private final ChatModel chatModel;

    public MerchantAgentService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Process a merchant's chat message through the agent pipeline.
     */
    public com.mercala.agent.chat.ChatResponse chat(ChatRequest request) {
        UUID tenantId = request.tenantId();
        UUID userId = request.userId();

        log.info("Merchant agent chat — tenant={}, user={}, message='{}'",
                tenantId, userId, truncate(request.message(), 80));

        // 1. Set tenant context for tool functions
        AgentContext.set(new AgentContext(tenantId, userId, "MERCHANT_OWNER"));

        try {
            // 2. Build messages
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.add(new UserMessage(request.message()));

            // 3. Build options with function calling
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withFunctions(MERCHANT_TOOLS)
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

            log.info("Merchant agent response generated — length={}", reply.length());

            return new com.mercala.agent.chat.ChatResponse(
                    reply,
                    List.of(), // Tool invocations populated by Spring AI callback hooks in future
                    request.conversationId() != null ? request.conversationId() : UUID.randomUUID().toString(),
                    Instant.now()
            );

        } finally {
            AgentContext.clear();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
