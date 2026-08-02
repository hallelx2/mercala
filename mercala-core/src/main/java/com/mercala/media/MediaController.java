package com.mercala.media;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing endpoints to trigger administrative actions on the Media context.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final ImageResultKafkaConsumer imageResultKafkaConsumer;

    public MediaController(@Autowired(required = false) ImageResultKafkaConsumer imageResultKafkaConsumer) {
        this.imageResultKafkaConsumer = imageResultKafkaConsumer;
    }

    /**
     * Programmatically triggers a seek to beginning on the consumer group's partitions,
     * initiating a complete replay of image result events.
     */
    // Documented as public because SecurityConfig permits /api/media/** without
    // authentication. Whether that rule should exist is tracked in HAL-495; until it
    // changes, the document must describe the API as it actually behaves.
    @SecurityRequirements
    @PostMapping("/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerReplay() {
        if (imageResultKafkaConsumer != null) {
            imageResultKafkaConsumer.replayAll();
        } else {
            throw new IllegalStateException("Kafka consumer is not available");
        }
    }
}
