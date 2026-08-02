package com.mercala.agent.tool;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.kafka.ImageRequestProducer;
import com.mercala.agent.tool.ToolPayloads.EnhanceProductImageArgs;
import com.mercala.agent.tool.ToolPayloads.RequestProductImageArgs;

/**
 * Spring AI tool/function definitions for image generation requests.
 */
@Configuration
public class ImageTools {

    private static final Logger log = LoggerFactory.getLogger(ImageTools.class);

    private final ImageRequestProducer imageRequestProducer;

    public ImageTools(ImageRequestProducer imageRequestProducer) {
        this.imageRequestProducer = imageRequestProducer;
    }

    @Bean
    @Description("Requests asynchronous AI image generation for a product catalog item. " +
            "Requires a product ID and a detailed prompt describing what the image should show. " +
            "Returns a status map indicating the request was queued.")
    public Function<RequestProductImageArgs, Map<String, Object>> requestProductImage() {
        return args -> ToolActivity.observe("requestProductImage", args, () -> {
            log.info("Tool: requestProductImage invoked — productId={}, prompt='{}'",
                    args.productId(), args.prompt());

            UUID productId = UUID.fromString(args.productId());
            imageRequestProducer.sendImageRequest(productId, args.prompt());

            // Generation is asynchronous — the merchant will not see a picture for several
            // seconds, so tell the client a job exists rather than letting the turn end
            // looking like nothing happened.
            AgentContext.currentChannel().custom("image_job", Map.of(
                    "productId", args.productId(),
                    "mode", "GENERATE",
                    "phase", "queued",
                    "prompt", args.prompt() == null ? "" : args.prompt()));

            return Map.of(
                    "status", "QUEUED",
                    "productId", args.productId(),
                    "message", "Image generation request published successfully to Kafka."
            );
        });
    }

    @Bean
    @Description("Retouches a photograph the merchant uploaded, rather than inventing a new image. "
            + "Use this whenever the merchant has supplied their own photo — it is almost always what "
            + "they want for a real product. Requires the product ID, the uploaded image's URL, and an "
            + "instruction describing the change ('remove the background and light it like a studio "
            + "shot'). Optionally takes a strength between 0 and 1: low values (0.2–0.4) keep the "
            + "product recognisably the same, high values redraw it. Returns once the job is queued; "
            + "the result arrives asynchronously.")
    public Function<EnhanceProductImageArgs, Map<String, Object>> enhanceProductImage() {
        return args -> ToolActivity.observe("enhanceProductImage", args, () -> {
            log.info("Tool: enhanceProductImage invoked — productId={}, source='{}', instruction='{}'",
                    args.productId(), args.sourceImageUrl(), args.instruction());

            if (args.sourceImageUrl() == null || args.sourceImageUrl().isBlank()) {
                // Answered to the model rather than thrown: it can recover by asking the
                // merchant to attach a photo, which is the actual next step.
                return Map.of(
                        "status", "MISSING_SOURCE",
                        "message", "No uploaded image was given. Ask the merchant to attach a photo first.");
            }

            UUID productId = UUID.fromString(args.productId());
            imageRequestProducer.sendEnhancementRequest(
                    productId, args.sourceImageUrl(), args.instruction(), args.strength());

            // The client renders this as an enhancement card — original on one side, a
            // progress state on the other — so the merchant watches it happen instead of
            // waiting on a sentence that says it is happening.
            AgentContext.currentChannel().custom("image_job", Map.of(
                    "productId", args.productId(),
                    "mode", "ENHANCE",
                    "phase", "queued",
                    "sourceImageUrl", args.sourceImageUrl(),
                    "instruction", args.instruction() == null ? "" : args.instruction()));

            return Map.of(
                    "status", "QUEUED",
                    "productId", args.productId(),
                    "sourceImageUrl", args.sourceImageUrl(),
                    "message", "Enhancement request published. The retouched image will attach to the product shortly."
            );
        });
    }
}
