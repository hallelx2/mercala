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

    @Test
    void enhanceProductImage_queuesTheMerchantsOwnPhotoForRetouching() {
        UUID productId = UUID.randomUUID();
        String source = "http://localhost:9000/mercala-images/t/uploads/shirt.jpg";
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));

        Map<String, Object> result = tools.enhanceProductImage().apply(
                new ToolPayloads.EnhanceProductImageArgs(
                        productId.toString(), source, "remove the background", 0.35));

        assertThat(result.get("status")).isEqualTo("QUEUED");
        assertThat(result.get("sourceImageUrl")).isEqualTo(source);
        verify(mockProducer).sendEnhancementRequest(productId, source, "remove the background", 0.35);
    }

    /**
     * Answered to the model rather than thrown, because the model can recover from this:
     * the next move is to ask the merchant for a photo, which it can only do if it is told
     * what went wrong instead of having the tool fail.
     */
    @Test
    void enhanceProductImage_withNoSourceTellsTheModelToAskForOne() {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));

        Map<String, Object> result = tools.enhanceProductImage().apply(
                new ToolPayloads.EnhanceProductImageArgs(
                        UUID.randomUUID().toString(), "  ", "clean it up", null));

        assertThat(result.get("status")).isEqualTo("MISSING_SOURCE");
        Mockito.verifyNoInteractions(mockProducer);
    }

    /** The client renders this as the enhancement card, so it has to carry the original. */
    @Test
    void enhanceProductImage_announcesTheJobToTheClient() {
        UUID productId = UUID.randomUUID();
        String source = "http://localhost:9000/mercala-images/t/uploads/shirt.jpg";
        com.mercala.agent.agui.AgentRunChannel channel =
                com.mercala.agent.agui.AgentRunChannel.active("run-1");
        AgentContext.set(new AgentContext(
                UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER", channel));

        tools.enhanceProductImage().apply(new ToolPayloads.EnhanceProductImageArgs(
                productId.toString(), source, "studio lighting", 0.4));
        channel.close();

        com.mercala.agent.agui.AgUiEvent.Custom job = channel.events()
                .filter(com.mercala.agent.agui.AgUiEvent.Custom.class::isInstance)
                .map(com.mercala.agent.agui.AgUiEvent.Custom.class::cast)
                .blockFirst();

        assertThat(job).isNotNull();
        assertThat(job.name()).isEqualTo("image_job");
        assertThat(job.value()).containsEntry("mode", "ENHANCE");
        assertThat(job.value()).containsEntry("sourceImageUrl", source);
    }
}
