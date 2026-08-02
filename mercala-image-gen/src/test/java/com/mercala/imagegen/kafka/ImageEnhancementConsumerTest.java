package com.mercala.imagegen.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercala.contracts.event.ImageMode;
import com.mercala.contracts.event.ImageRequestEvent;
import com.mercala.imagegen.provider.ImageProvider;
import com.mercala.imagegen.storage.SourceImageLoader;
import com.mercala.imagegen.storage.StorageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumer's branch: one topic, two operations. Exercised directly rather than through
 * an embedded broker, because what is under test is the branch and the ordering of the calls
 * inside it, not Kafka.
 */
class ImageEnhancementConsumerTest {

    private ImageProvider provider;
    private StorageService storage;
    private ImageResultProducer results;
    private SourceImageLoader loader;
    private ImageRequestKafkaConsumer consumer;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provider = Mockito.mock(ImageProvider.class);
        storage = Mockito.mock(StorageService.class);
        results = Mockito.mock(ImageResultProducer.class);
        loader = Mockito.mock(SourceImageLoader.class);
        consumer = new ImageRequestKafkaConsumer(
                provider, storage, results, new InMemoryIdempotencyRegistry(), loader);
    }

    private void consume(ImageRequestEvent event) {
        consumer.consume(event, tenantId.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private ImageRequestEvent enhancement() {
        return ImageRequestEvent.enhance(
                productId, tenantId,
                "http://localhost:9000/mercala-images/" + tenantId + "/uploads/photo.jpg",
                "remove the background", 0.4);
    }

    @Test
    void anEnhancementLoadsTheSourceRetouchesItAndStoresTheResultAsAVariant() {
        when(provider.supportsEnhancement()).thenReturn(true);
        when(loader.load(anyString())).thenReturn("original".getBytes());
        when(provider.enhanceImage(any(), anyString(), anyDouble())).thenReturn("retouched".getBytes());
        when(storage.uploadImage(any(), any(), any(), anyString()))
                .thenReturn("http://localhost:9000/mercala-images/enhanced.jpg");

        consume(enhancement());

        verify(provider).enhanceImage(any(), eq("remove the background"), eq(0.4));
        // As a variant, so it lands beside the original instead of overwriting the
        // product's generated image.
        verify(storage).uploadImage(eq(tenantId), eq(productId), any(), eq("enhanced"));
        verify(provider, never()).generateImage(anyString());
    }

    /** The merchant has to be able to see what it was made from. */
    @Test
    void theResultEventCarriesTheOriginalAlongsideTheRetouchedImage() {
        String source = "http://localhost:9000/mercala-images/" + tenantId + "/uploads/photo.jpg";
        when(provider.supportsEnhancement()).thenReturn(true);
        when(loader.load(anyString())).thenReturn("original".getBytes());
        when(provider.enhanceImage(any(), anyString(), anyDouble())).thenReturn("retouched".getBytes());
        when(storage.uploadImage(any(), any(), any(), anyString())).thenReturn("http://storage/enhanced.jpg");

        consume(ImageRequestEvent.enhance(productId, tenantId, source, "clean it up", 0.3));

        verify(results).publishEnhancementResult(
                eq(productId), eq(tenantId), eq("http://storage/enhanced.jpg"), eq(source));
    }

    /**
     * Fail before the work, not after it. A merchant who uploads a photo and waits should
     * not be told at the end that nothing in the chain could ever have done this.
     */
    @Test
    void anUnsupportedChainIsRefusedBeforeTheSourceIsEvenFetched() {
        when(provider.supportsEnhancement()).thenReturn(false);

        assertThatThrownBy(() -> consume(enhancement()))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        verify(loader, never()).load(anyString());
        verify(provider, never()).enhanceImage(any(), anyString(), anyDouble());
    }

    @Test
    void aGenerateRequestIsUntouchedByAnyOfThis() {
        when(provider.generateImage(anyString())).thenReturn("generated".getBytes());
        when(storage.uploadImage(any(), any(), any())).thenReturn("http://storage/generated.png");

        consume(new ImageRequestEvent(productId, tenantId, "a navy linen shirt"));

        verify(provider).generateImage("a navy linen shirt");
        verify(results).publishImageResult(productId, tenantId, "http://storage/generated.png");
        verify(loader, never()).load(anyString());
    }

    @Test
    void aTenantHeaderThatDisagreesWithTheEventIsRejected() {
        assertThatThrownBy(() -> consumer.consume(
                enhancement(), UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The topic is replayable, so a replay after this deploy reads events written before
     * the mode field existed. Those must still be understood as generation requests.
     */
    @Test
    void anEventWrittenBeforeTheModeFieldExistedStillDeserialisesAsGeneration() throws Exception {
        String legacy = """
                {"eventId":"%s","productId":"%s","tenantId":"%s","prompt":"a navy linen shirt"}
                """.formatted(UUID.randomUUID(), productId, tenantId);

        ImageRequestEvent event = new ObjectMapper().readValue(legacy, ImageRequestEvent.class);

        assertThat(event.mode()).isEqualTo(ImageMode.GENERATE);
        assertThat(event.isEnhancement()).isFalse();
        assertThat(event.prompt()).isEqualTo("a navy linen shirt");
        assertThat(event.strength()).isEqualTo(ImageRequestEvent.DEFAULT_STRENGTH);
    }

    /** A client asking for 3.0 gets the strongest the models accept, not a provider error. */
    @Test
    void strengthIsClampedIntoTheRangeProvidersAccept() {
        assertThat(ImageRequestEvent.enhance(productId, tenantId, "u", "i", 3.0).strength()).isEqualTo(1.0);
        assertThat(ImageRequestEvent.enhance(productId, tenantId, "u", "i", -1.0).strength()).isEqualTo(0.0);
        assertThat(ImageRequestEvent.enhance(productId, tenantId, "u", "i", null).strength())
                .isEqualTo(ImageRequestEvent.DEFAULT_STRENGTH);
    }
}
