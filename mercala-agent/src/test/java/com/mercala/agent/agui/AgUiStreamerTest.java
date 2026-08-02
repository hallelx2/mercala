package com.mercala.agent.agui;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.mercala.agent.chat.AgentContext;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A run's shape is a contract: clients gate their "still working" state on the lifecycle
 * frames, so a run that ends without one leaves a spinner turning forever, and a run with
 * two terminal frames leaves a client that has already stopped listening.
 */
class AgUiStreamerTest {

    private final ChatModel chatModel = Mockito.mock(ChatModel.class);
    private final AgUiStreamer streamer = new AgUiStreamer(chatModel, Duration.ofSeconds(5));

    private final AgentContext context =
            new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER");

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private List<AgUiEvent> run(AgentRunChannel channel) {
        List<AgUiEvent> events = streamer
                .stream(new Prompt("x"), context, channel, "thread-1", "run-1")
                .collectList()
                .block(Duration.ofSeconds(20));
        assertThat(events).isNotNull();
        return events;
    }

    @Test
    void aRunOpensWithRunStartedAndClosesWithRunFinished() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("Done.")));

        List<AgUiEvent> events = run(AgentRunChannel.active("run-1"));

        assertThat(events.get(0)).isInstanceOf(AgUiEvent.RunStarted.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgUiEvent.RunFinished.class);
        assertThat(events).noneMatch(AgUiEvent.RunError.class::isInstance);
    }

    @Test
    void theOpeningStateSnapshotGivesTheClientSomethingToPatch() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("ok")));

        AgUiEvent.StateSnapshot snapshot = run(AgentRunChannel.active("run-1")).stream()
                .filter(AgUiEvent.StateSnapshot.class::isInstance)
                .map(AgUiEvent.StateSnapshot.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(snapshot.snapshot()).containsKeys("phase", "activity", "threadId", "runId");
        assertThat(snapshot.snapshot().get("phase")).isEqualTo("thinking");
    }

    @Test
    void textIsFramedByExactlyOneStartAndOneEnd() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf("Creating "), responseOf("your product")));

        List<AgUiEvent> events = run(AgentRunChannel.active("run-1"));

        assertThat(events.stream().filter(AgUiEvent.TextMessageStart.class::isInstance)).hasSize(1);
        assertThat(events.stream().filter(AgUiEvent.TextMessageEnd.class::isInstance)).hasSize(1);
        assertThat(events.stream()
                .filter(AgUiEvent.TextMessageContent.class::isInstance)
                .map(AgUiEvent.TextMessageContent.class::cast)
                .map(AgUiEvent.TextMessageContent::delta))
                .containsExactly("Creating ", "your product");
    }

    /**
     * A turn that is entirely tool calls — "add stock to all three variants" — produces no
     * text at all. Emitting an empty message envelope would leave the client rendering a
     * blank assistant bubble.
     */
    @Test
    void aTurnWithNoTextEmitsNoTextFramesAtAll() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("")));

        List<AgUiEvent> events = run(AgentRunChannel.active("run-1"));

        assertThat(events).noneMatch(AgUiEvent.TextMessageStart.class::isInstance);
        assertThat(events).noneMatch(AgUiEvent.TextMessageEnd.class::isInstance);
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgUiEvent.RunFinished.class);
    }

    @Test
    void toolEventsFromTheChannelAppearInTheSameStreamAsText() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");

        // Stands in for Spring AI executing a tool inside the model call, on its own thread.
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            channel.toolStarted("tc1", "createProduct", "{\"name\":\"Linen shirt\"}");
            channel.toolFinished("tc1", "{\"id\":\"p1\"}", 12L, false);
            return Flux.just(responseOf("Added it."));
        });

        List<AgUiEvent> events = run(channel);

        assertThat(events).anyMatch(AgUiEvent.ToolCallStart.class::isInstance);
        assertThat(events).anyMatch(AgUiEvent.ToolCallResult.class::isInstance);
        assertThat(events).anyMatch(AgUiEvent.TextMessageContent.class::isInstance);
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgUiEvent.RunFinished.class);
    }

    @Test
    void aModelFailureEndsTheRunWithRunErrorRatherThanAFailedStream() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("provider exploded")));

        List<AgUiEvent> events = run(AgentRunChannel.active("run-1"));

        AgUiEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgUiEvent.RunError.class);
        assertThat(((AgUiEvent.RunError) last).code()).isEqualTo("AGENT_ERROR");
        assertThat(events).noneMatch(AgUiEvent.RunFinished.class::isInstance);
    }

    /** Vendor exception text can carry prompt fragments, so it must not reach the merchant. */
    @Test
    void theErrorFrameDoesNotLeakTheUnderlyingFailure() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("api key sk-live-123 rejected")));

        AgUiEvent.RunError error = (AgUiEvent.RunError) run(AgentRunChannel.active("run-1")).stream()
                .filter(AgUiEvent.RunError.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertThat(error.message()).doesNotContain("sk-live-123");
    }

    @Test
    void aStalledModelTimesOutIntoARunErrorInsteadOfHangingTheStream() {
        AgUiStreamer impatient = new AgUiStreamer(chatModel, Duration.ofMillis(150));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        StepVerifier.create(impatient.stream(
                        new Prompt("x"), context, AgentRunChannel.active("run-1"), "thread-1", "run-1"))
                .expectNextMatches(AgUiEvent.RunStarted.class::isInstance)
                .expectNextMatches(AgUiEvent.StateSnapshot.class::isInstance)
                .expectNextMatches(AgUiEvent.StepStarted.class::isInstance)
                .expectNextMatches(event -> event instanceof AgUiEvent.RunError error
                        && "STREAM_TIMEOUT".equals(error.code()))
                .verifyComplete();
    }

    /**
     * The security property from HAL-515, re-asserted on this path: tools run on threads
     * the streamer never sees, and a tool without tenant context calls core unscoped.
     */
    @Test
    void tenantContextReachesTheThreadRunningTheModelCall() {
        AtomicReference<AgentContext> seen = new AtomicReference<>();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            seen.set(AgentContext.currentOrNull());
            return Flux.just(responseOf("ok"));
        });

        run(AgentRunChannel.active("run-1"));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().tenantId()).isEqualTo(context.tenantId());
    }

    /** Tools reach their channel through the context, so it has to be the run's own channel. */
    @Test
    void theRunsChannelIsReachableFromTheToolsThread() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");
        AtomicReference<AgentRunChannel> seen = new AtomicReference<>();

        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            seen.set(AgentContext.currentChannel());
            return Flux.just(responseOf("ok"));
        });

        run(channel);

        assertThat(seen.get()).isSameAs(channel);
    }
}
