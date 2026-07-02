package com.mercala.imagegen;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.boot.test.mock.mockito.MockBean;
import com.mercala.imagegen.storage.StorageService;
import com.mercala.imagegen.provider.ImageProvider;

@SpringBootTest(properties = {
    "spring.ai.openai.image.enabled=false",
    "spring.ai.openai.api-key=dummy",
    "mercala.storage.endpoint=http://localhost:9000",
    "mercala.storage.access-key=dummy",
    "mercala.storage.secret-key=dummy",
    "mercala.storage.bucket=dummy"
})
@ActiveProfiles("test")
class MercalaImageGenApplicationTest {

    @MockBean
    private StorageService storageService;

    @MockBean
    private ImageProvider imageProvider;

    @Test
    void contextLoads() {
        // Assert that the Spring application context loads successfully.
    }
}
