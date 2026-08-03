package com.mercala.media;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Administrative actions on the Media context.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final ImageResultKafkaConsumer imageResultKafkaConsumer;

    public MediaController(@Autowired(required = false) ImageResultKafkaConsumer imageResultKafkaConsumer) {
        this.imageResultKafkaConsumer = imageResultKafkaConsumer;
    }

    /**
     * Seeks the consumer group's partitions back to the beginning, replaying every image
     * result event ever published.
     *
     * <p>Restricted to {@code PLATFORM_ADMIN}, and the role is the point rather than a
     * formality: replay is not scoped to a tenant. It re-reads the whole topic across every
     * store on the platform, so no merchant role should reach it however good their reason.
     *
     * <p>Until HAL-495 this needed no token at all — {@code /api/media/**} was
     * {@code permitAll}, so anyone who found the path could trigger a full replay as often
     * as they liked, which is free amplification against Kafka and the image pipeline.
     */
    @Operation(
            summary = "Replay every image result event",
            description = """
                    Platform operators only. Seeks the consumer group to the beginning of
                    `image.results`, re-attaching imagery across every tenant. A recovery
                    tool for after a consumer defect, not a routine operation.
                    """)
    @PostMapping("/replay")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerReplay() {
        if (imageResultKafkaConsumer != null) {
            imageResultKafkaConsumer.replayAll();
        } else {
            throw new IllegalStateException("Kafka consumer is not available");
        }
    }
}
