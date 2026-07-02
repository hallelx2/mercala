package com.mercala.agent.tool;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that asserts all tool function beans are registered in the Spring context
 * and have the correct return types.
 */
@SpringBootTest(properties = {
    "spring.ai.openai.chat.enabled=false",
    "spring.ai.openai.embedding.enabled=false",
    "mercala.core.base-url=http://localhost:8080"
})
@ActiveProfiles("test")
class CatalogToolsRegistrationTest {

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

    @Autowired
    private ApplicationContext context;

    @Test
    void createProductBeanIsRegistered() {
        Object bean = context.getBean("createProduct");
        assertThat(bean).isInstanceOf(Function.class);
    }

    @Test
    void getProductBeanIsRegistered() {
        Object bean = context.getBean("getProduct");
        assertThat(bean).isInstanceOf(Function.class);
    }

    @Test
    void searchCatalogBeanIsRegistered() {
        Object bean = context.getBean("searchCatalog");
        assertThat(bean).isInstanceOf(Function.class);
    }

    @Test
    void updateInventoryBeanIsRegistered() {
        Object bean = context.getBean("updateInventory");
        assertThat(bean).isInstanceOf(Function.class);
    }

    @Test
    void allFourToolBeansExist() {
        String[] expectedBeans = {"createProduct", "getProduct", "searchCatalog", "updateInventory"};
        for (String name : expectedBeans) {
            assertThat(context.containsBean(name))
                    .as("Bean '%s' should be registered", name)
                    .isTrue();
        }
    }
}
