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
 * Unit tests for MerchantAgentService verifying:
 *   - AgentContext is set before model call and cleared after
 *   - System prompt includes merchant persona
 *   - Function names are passed to OpenAI options
 *   - Response is correctly constructed
 */
class MerchantAgentServiceTest {

    private ChatModel chatModel;
    private MerchantAgentService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        service = new MerchantAgentService(chatModel, new AgentStreamer(chatModel));
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
    void chat_setsAndClearsAgentContext() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            // Context should be available during the call
            AgentContext ctx = AgentContext.current();
            assertThat(ctx.tenantId()).isNotNull();
            assertThat(ctx.userRole()).isEqualTo("MERCHANT_OWNER");
            return mockResponse("Done!");
        });

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ChatRequest req = new ChatRequest("Add a widget", tenantId, userId, null);

        service.chat(req);

        // After call, context should be cleared (shouldn't leak)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, AgentContext::current);
    }

    @Test
    void chat_sendsSystemPromptWithMerchantPersona() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture())).thenReturn(mockResponse("I'll add that product for you."));

        ChatRequest req = new ChatRequest(
                "add a navy linen shirt, sizes S-L, $49",
                UUID.randomUUID(), UUID.randomUUID(), "conv-123"
        );

        service.chat(req);

        Prompt prompt = captor.getValue();
        assertThat(prompt.getInstructions()).hasSize(2); // system + user
        String systemContent = prompt.getInstructions().get(0).getContent();
        assertThat(systemContent).contains("Mercala merchant assistant");
        assertThat(systemContent).contains("createProduct");
        assertThat(systemContent).contains("searchCatalog");
        assertThat(systemContent).contains("updateInventory");
    }

    @Test
    void chat_includesUserMessageContent() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture())).thenReturn(mockResponse("Added!"));

        ChatRequest req = new ChatRequest("add 50 units of SKU-001", UUID.randomUUID(), UUID.randomUUID(), null);
        service.chat(req);

        String userContent = captor.getValue().getInstructions().get(1).getContent();
        assertThat(userContent).isEqualTo("add 50 units of SKU-001");
    }

    @Test
    void chat_returnsResponseWithConversationId() {
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse("Product created!"));

        ChatRequest req = new ChatRequest("create a widget", UUID.randomUUID(), UUID.randomUUID(), "conv-abc");
        com.mercala.agent.chat.ChatResponse response = service.chat(req);

        assertThat(response.message()).isEqualTo("Product created!");
        assertThat(response.conversationId()).isEqualTo("conv-abc");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void chat_generatesConversationIdWhenNotProvided() {
        when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse("OK"));

        ChatRequest req = new ChatRequest("search for shirts", UUID.randomUUID(), UUID.randomUUID(), null);
        com.mercala.agent.chat.ChatResponse response = service.chat(req);

        assertThat(response.conversationId()).isNotNull().isNotBlank();
    }

    @Test
    void chat_handlesNullResponse() {
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        ChatRequest req = new ChatRequest("do something", UUID.randomUUID(), UUID.randomUUID(), null);
        com.mercala.agent.chat.ChatResponse response = service.chat(req);

        assertThat(response.message()).isEmpty();
    }

    @Test
    void chat_propagatesTenantId() {
        UUID tenantId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            assertThat(AgentContext.current().tenantId()).isEqualTo(tenantId);
            return mockResponse("OK");
        });

        ChatRequest req = new ChatRequest("list products", tenantId, UUID.randomUUID(), null);
        service.chat(req);
    }
}
