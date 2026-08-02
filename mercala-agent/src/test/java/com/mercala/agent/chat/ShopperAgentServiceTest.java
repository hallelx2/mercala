package com.mercala.agent.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ShopperAgentService verifying:
 *   - AgentContext is set with SHOPPER role
 *   - System prompt includes shopper persona and grounding rules
 *   - Only read-only tools (searchCatalog, getProduct) are exposed
 *   - No write tools (createProduct, updateInventory) are available
 */
class ShopperAgentServiceTest {

    private ChatModel chatModel;
    private ShopperAgentService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        service = new ShopperAgentService(chatModel, new AgentStreamer(chatModel, java.time.Duration.ofSeconds(30)));
    }

    @AfterEach
    void tearDown() {
        try { AgentContext.clear(); } catch (Exception ignored) {}
    }

    private ChatResponse mockResponse(String content) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation gen = new Generation(msg);
        return new ChatResponse(List.of(gen));
    }

    @Test
    void chat_setsShopperRole() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            AgentContext ctx = AgentContext.current();
            assertThat(ctx.userRole()).isEqualTo("SHOPPER");
            return mockResponse("Here are some great options!");
        });

        ChatRequest req = new ChatRequest("a gift under $50 for a hiker", UUID.randomUUID(), UUID.randomUUID(), null);
        service.chat(req);
    }

    @Test
    void chat_clearsContextAfterCall() {
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse("Found some!"));

        ChatRequest req = new ChatRequest("running shoes", UUID.randomUUID(), UUID.randomUUID(), null);
        service.chat(req);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, AgentContext::current);
    }

    @Test
    void chat_systemPromptContainsShopperPersona() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture())).thenReturn(mockResponse("Great choices!"));

        ChatRequest req = new ChatRequest("waterproof jacket", UUID.randomUUID(), UUID.randomUUID(), null);
        service.chat(req);

        String systemContent = captor.getValue().getInstructions().get(0).getContent();
        assertThat(systemContent).contains("Mercala shopping assistant");
        assertThat(systemContent).contains("Grounded answers only");
    }

    @Test
    void chat_systemPromptIncludesSearchToolButNotCreateProduct() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture())).thenReturn(mockResponse("Here you go"));

        ChatRequest req = new ChatRequest("find me a laptop bag", UUID.randomUUID(), UUID.randomUUID(), null);
        service.chat(req);

        String systemContent = captor.getValue().getInstructions().get(0).getContent();
        assertThat(systemContent).contains("searchCatalog");
        assertThat(systemContent).contains("getProduct");
        // Shopper should NOT see write tools in the system prompt
        assertThat(systemContent).doesNotContain("createProduct");
        assertThat(systemContent).doesNotContain("updateInventory");
    }

    @Test
    void chat_propagatesTenantId() {
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            assertThat(AgentContext.current().tenantId()).isEqualTo(tenantId);
            return mockResponse("Found products");
        });

        ChatRequest req = new ChatRequest("summer dress", tenantId, UUID.randomUUID(), null);
        service.chat(req);
    }

    @Test
    void chat_returnsResponseWithCorrectConversationId() {
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse("I found 3 options for you!"));

        ChatRequest req = new ChatRequest("birthday gift", UUID.randomUUID(), UUID.randomUUID(), "shopper-conv-001");
        com.mercala.agent.chat.ChatResponse response = service.chat(req);

        assertThat(response.message()).isEqualTo("I found 3 options for you!");
        assertThat(response.conversationId()).isEqualTo("shopper-conv-001");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void chat_generatesConversationIdWhenMissing() {
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse("OK"));

        ChatRequest req = new ChatRequest("cheap earbuds", UUID.randomUUID(), UUID.randomUUID(), null);
        com.mercala.agent.chat.ChatResponse response = service.chat(req);

        assertThat(response.conversationId()).isNotNull().isNotBlank();
    }

    @Test
    void chat_passesUserMessage() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture())).thenReturn(mockResponse("Found it!"));

        ChatRequest req = new ChatRequest("a gift under $50 for a hiker", UUID.randomUUID(), UUID.randomUUID(), null);
        service.chat(req);

        String userContent = captor.getValue().getInstructions().get(1).getContent();
        assertThat(userContent).isEqualTo("a gift under $50 for a hiker");
    }
}
