package com.mercala.agent.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for MerchantChatController verifying the REST endpoint.
 */
@SpringBootTest(properties = {
    "spring.ai.openai.chat.enabled=false",
    "spring.ai.openai.embedding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantChatControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public ChatModel mockChatModel() {
            ChatModel mock = Mockito.mock(ChatModel.class);
            AssistantMessage msg = new AssistantMessage("Product 'Linen Shirt' created with 3 variants (S, M, L) at $49.00 each.");
            Generation gen = new Generation(msg);
            ChatResponse response = new ChatResponse(List.of(gen));
            Mockito.when(mock.call(any(Prompt.class))).thenReturn(response);
            return mock;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void postChat_returnsAgentResponse() throws Exception {
        ChatRequest request = new ChatRequest(
                "add a navy linen shirt, sizes S-L, $49",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "conv-test-001"
        );

        mockMvc.perform(post("/api/agent/merchant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product 'Linen Shirt' created with 3 variants (S, M, L) at $49.00 each."))
                .andExpect(jsonPath("$.conversationId").value("conv-test-001"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void postChat_rejectsEmptyMessage() throws Exception {
        ChatRequest request = new ChatRequest(
                "",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        mockMvc.perform(post("/api/agent/merchant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postChat_rejectsNullMessage() throws Exception {
        String payload = """
                {
                    "tenantId": "%s",
                    "userId": "%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/agent/merchant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }
}
