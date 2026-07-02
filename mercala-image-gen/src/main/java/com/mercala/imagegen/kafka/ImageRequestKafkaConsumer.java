package com.mercala.imagegen.kafka;

import com.mercala.contracts.event.ImageRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ImageRequestKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageRequestKafkaConsumer.class);

    private final List<ImageRequestEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(
            topics = "${mercala.kafka.image-requests-topic:image.requests}",
            groupId = "${spring.kafka.consumer.group-id:mercala-image-gen-group}"
    )
    public void consume(ImageRequestEvent event) {
        log.info("Received image request event: productId={}, tenantId={}, prompt='{}'",
                event.productId(), event.tenantId(), event.prompt());
        receivedEvents.add(event);
    }

    public List<ImageRequestEvent> getReceivedEvents() {
        return new ArrayList<>(receivedEvents);
    }

    public void clearReceivedEvents() {
        receivedEvents.clear();
    }
}
