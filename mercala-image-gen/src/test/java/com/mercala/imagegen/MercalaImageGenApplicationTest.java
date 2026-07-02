package com.mercala.imagegen;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.ai.openai.image.enabled=false",
    "spring.ai.openai.api-key=dummy"
})
@ActiveProfiles("test")
class MercalaImageGenApplicationTest {

    @Test
    void contextLoads() {
        // Assert that the Spring application context loads successfully.
    }
}
