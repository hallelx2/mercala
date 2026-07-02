package com.mercala.agent.tool;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.kafka.ImageRequestProducer;
import com.mercala.agent.tool.ToolPayloads.RequestProductImageArgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class ImageToolsTest {

    private final ImageRequestProducer mockProducer = Mockito.mock(ImageRequestProducer.class);
    private final ImageTools tools = new ImageTools(mockProducer);

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void requestProductImage_invokesProducerWithArgs() {
        UUID productId = UUID.randomUUID();
        String prompt = "A photorealistic shot of linen trousers";

        // Set context (required by producer)
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));

        Function<RequestProductImageArgs, Map<String, Object>> func = tools.requestProductImage();
        Map<String, Object> result = func.apply(new RequestProductImageArgs(productId.toString(), prompt));

        assertThat(result.get("status")).isEqualTo("QUEUED");
        assertThat(result.get("productId")).isEqualTo(productId.toString());
        verify(mockProducer).sendImageRequest(productId, prompt);
    }
}
