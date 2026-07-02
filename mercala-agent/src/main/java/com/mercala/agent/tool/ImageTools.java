package com.mercala.agent.tool;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.mercala.agent.kafka.ImageRequestProducer;
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
        return args -> {
            log.info("Tool: requestProductImage invoked — productId={}, prompt='{}'",
                    args.productId(), args.prompt());

            try {
                UUID productId = UUID.fromString(args.productId());
                imageRequestProducer.sendImageRequest(productId, args.prompt());
                return Map.of(
                        "status", "QUEUED",
                        "productId", args.productId(),
                        "message", "Image generation request published successfully to Kafka."
                );
            } catch (Exception e) {
                log.error("Tool: requestProductImage failed", e);
                return Map.of("status", "FAILED", "error", e.getMessage());
            }
        };
    }
}
