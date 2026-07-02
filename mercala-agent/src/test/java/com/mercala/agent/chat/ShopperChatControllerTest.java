package com.mercala.agent.chat;

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
 * Integration test for ShopperChatController verifying the REST endpoint.
 */
@SpringBootTest(properties = {
    "spring.ai.openai.chat.enabled=false",
    "spring.ai.openai.embedding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShopperChatControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public ChatModel mockChatModel() {
            ChatModel mock = Mockito.mock(ChatModel.class);
            AssistantMessage msg = new AssistantMessage(
                    "I found 2 great options for hikers under $50:\n" +
                    "1. **Trail Water Bottle** - $24.99 - Durable and lightweight\n" +
                    "2. **Hiking Sock Set** - $19.99 - Merino wool, moisture-wicking"
            );
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
    void postChat_returnsGroundedResponse() throws Exception {
        ChatRequest request = new ChatRequest(
                "a gift under $50 for a hiker",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "shopper-conv-001"
        );

        mockMvc.perform(post("/api/agent/shopper/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Trail Water Bottle")))
                .andExpect(jsonPath("$.conversationId").value("shopper-conv-001"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void postChat_rejectsEmptyMessage() throws Exception {
        ChatRequest request = new ChatRequest("", UUID.randomUUID(), UUID.randomUUID(), null);

        mockMvc.perform(post("/api/agent/shopper/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postChat_shopperEndpointIsDistinctFromMerchant() throws Exception {
        ChatRequest request = new ChatRequest(
                "show me running shoes",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        // Shopper endpoint works
        mockMvc.perform(post("/api/agent/shopper/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // Merchant endpoint also works (different path)
        mockMvc.perform(post("/api/agent/merchant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
