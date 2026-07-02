package com.mercala.agent;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.ai.openai.chat.enabled=false",
    "spring.ai.openai.embedding.enabled=false"
})
@ActiveProfiles("test")
class MercalaAgentApplicationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public ChatModel mockChatModel() {
            ChatModel mock = Mockito.mock(ChatModel.class);
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                   .thenReturn(new ChatResponse(java.util.List.of()));
            return mock;
        }
    }

    @Test
    void contextLoads() {
        // Asserts that the spring application context boots up successfully
    }
}
