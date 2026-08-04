package com.mercala.agent.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import com.mercala.agent.agui.AgUiEvent;
import com.mercala.agent.agui.AgUiStreamer;
import com.mercala.agent.agui.AgentRunChannel;
import com.mercala.agent.agui.ConversationBuilder;
import com.mercala.agent.agui.FrontendToolCallback;
import com.mercala.agent.agui.RunAgentInput;

import reactor.core.publisher.Flux;

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

    /**
     * Added to the persona on the AG-UI path only. The other endpoints answer into a plain
     * text box, where telling the model to "ask with a control the merchant can click"
     * would produce a question no client knows how to render.
     */
    private static final String AGENTIC_ADDENDUM = """

            ## Working with the merchant's interface
            This conversation is rendered by an interface that can show controls, not just text.

            - **Never write a question as prose.** If you find yourself about to type a
              numbered list of things you need, stop: that list is an `askUser` call. Text
              cannot be filled in, so a question written as a sentence costs the merchant a
              round of typing and gives you back something you then have to parse.
            - **Ask for everything at once.** One `askUser` call, with one entry in `fields`
              per thing you need — name, price, sizes, a photograph. Do not ask one question,
              wait, and ask the next; that is five round trips where one would do.
            - Give each field a `type`: `text`, `textarea`, `number`, `money`, `choice` (with
              `options`) or `image` when you want a photograph. Set `optional: true` on
              anything you can proceed without, so the merchant can skip it.
            - Suggest `options` wherever there is an obvious shortlist. The merchant can still
              answer with something else, so a good guess saves typing and a wrong one costs
              nothing.
            - Never invent a value and never proceed on an assumption you could have checked.
            - **Confirm before anything destructive or expensive.** Deletions, bulk price
              changes, anything that goes live to shoppers: call `confirmAction` first and say
              exactly what will happen.
            - **Propose, don't impose.** For a draft product or a set of field changes, call
              `proposeEdit` with the values you would have used. The merchant may correct them,
              and their corrected values are what you then apply — not your original proposal.
            - After calling any of these three, stop. The answer reaches you on the next turn.
            - Some tools run in the merchant's browser. Calling one dispatches it and returns
              immediately; its result also arrives on the next turn. Do not call it twice and do
              not invent what it returned.
            - The interface already shows every tool you call, its arguments and its result. Do
              not narrate mechanics — no "calling createProduct now". Say what it means for the
              store.
            """;

    private static final Set<String> MERCHANT_TOOLS = Set.of(
            "createProduct", "getProduct", "searchCatalog", "updateInventory",
            "requestProductImage", "enhanceProductImage"
    );

    /** The merchant tools plus the three that hand the turn back to a human. */
    private static final Set<String> AGENTIC_TOOLS = Stream.concat(
            MERCHANT_TOOLS.stream(),
            Stream.of("askUser", "confirmAction", "proposeEdit")
    ).collect(Collectors.toUnmodifiableSet());

    private final ChatModel chatModel;
    private final AgentStreamer agentStreamer;
    private final AgUiStreamer agUiStreamer;

    public MerchantAgentService(ChatModel chatModel, AgentStreamer agentStreamer, AgUiStreamer agUiStreamer) {
        this.chatModel = chatModel;
        this.agentStreamer = agentStreamer;
        this.agUiStreamer = agUiStreamer;
    }

    /**
     * Process a merchant's chat message through the agent pipeline.
     */
    public com.mercala.agent.chat.ChatResponse chat(ChatRequest request) {
        Resolved resolved = resolveIdentity(request);
        UUID tenantId = resolved.tenantId();
        UUID userId = resolved.userId();

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


    /**
     * Streaming variant of {@link #chat}. Same prompt, same tools, same tenant guard —
     * the difference is that the reply arrives incrementally instead of after the whole
     * turn completes.
     *
     * <p>Kept alongside the blocking method rather than replacing it: the SDK's simple path
     * and any non-browser caller still want one response object.
     */
    public Flux<ChatStreamEvent> chatStream(ChatRequest request) {
        Resolved resolved = resolveIdentity(request);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(request.message()));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withFunctions(MERCHANT_TOOLS)
                .build();

        return agentStreamer.stream(
                new Prompt(messages, options),
                new AgentContext(resolved.tenantId(), resolved.userId(), "MERCHANT_OWNER"),
                request.conversationId());
    }

    /**
     * The AG-UI path: one run of the agent, narrated as protocol events.
     *
     * <p>Three things differ from {@link #chatStream}, and each of them is what makes the
     * turn agentic rather than a monologue. The conversation is rebuilt from the client's
     * thread, so the agent can follow up and can act on an answer it asked for. The
     * human-in-the-loop tools are offered, so it can ask at all. And tools the client
     * declared are registered for this run only, so what the agent can do depends on where
     * the merchant is standing in the interface.
     */
    public Flux<AgUiEvent> agui(RunAgentInput input) {
        Resolved resolved = resolveIdentity(null, null);

        log.info("AG-UI run — tenant={}, user={}, thread={}, messages={}, clientTools={}",
                resolved.tenantId(), resolved.userId(), input.threadId(),
                input.messages() == null ? 0 : input.messages().size(), input.tools().size());

        AgentRunChannel channel = AgentRunChannel.active(input.runId());

        List<Message> messages = ConversationBuilder.build(
                SYSTEM_PROMPT + AGENTIC_ADDENDUM + renderContext(input.context()), input.messages());

        List<FunctionCallback> clientTools = input.tools().stream()
                .filter(tool -> tool != null && tool.name() != null && !tool.name().isBlank())
                // A client-declared tool may not take the name of a server one. Allowing it
                // would let a page shadow `createProduct` — the model would call what it
                // believed was the catalogue, and the browser would answer. That is a
                // capability the client is not supposed to have, whether it reached for it
                // deliberately or was talked into it.
                .filter(tool -> {
                    if (AGENTIC_TOOLS.contains(tool.name())) {
                        log.warn("Ignoring client-declared tool '{}': it shadows a server tool", tool.name());
                        return false;
                    }
                    return true;
                })
                .map(tool -> (FunctionCallback) new FrontendToolCallback(tool, channel))
                .toList();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withFunctions(AGENTIC_TOOLS)
                .withFunctionCallbacks(clientTools)
                .build();

        return agUiStreamer.stream(
                new Prompt(messages, options),
                new AgentContext(resolved.tenantId(), resolved.userId(), "MERCHANT_OWNER"),
                channel,
                input.threadId(),
                input.runId());
    }

    /**
     * Client context — what page the merchant is on, what they have selected — appended to
     * the system prompt rather than sent as a message, so it reads as ambient truth rather
     * than as something the merchant said and might be contradicting later.
     */
    private static String renderContext(List<RunAgentInput.ContextItem> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## What the merchant is currently looking at\n");
        for (RunAgentInput.ContextItem item : context) {
            if (item == null || item.value() == null) {
                continue;
            }
            sb.append("- ")
                    .append(item.description() == null ? "context" : item.description())
                    .append(": ")
                    .append(item.value())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * Tenant/user reconciliation, used by both the blocking and streaming paths.
     * Security-relevant: it rejects a request whose body claims a different tenant or
     * user than the authenticated session, so it must have exactly one implementation.
     */
    private Resolved resolveIdentity(ChatRequest request) {
        return resolveIdentity(request.tenantId(), request.userId());
    }

    /**
     * @param claimedTenantId tenant the caller asserts, or null to take the session's
     * @param claimedUserId   user the caller asserts, or null to take the session's
     */
    private Resolved resolveIdentity(UUID claimedTenantId, UUID claimedUserId) {
        UUID tenantId = claimedTenantId;
        UUID userId = claimedUserId;
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
