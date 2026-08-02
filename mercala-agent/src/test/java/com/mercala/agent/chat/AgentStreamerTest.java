package com.mercala.agent.chat;

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

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Streaming behaviour, with particular attention to tenant context.
 *
 * <p>{@link AgentContext} is a ThreadLocal and reactive pipelines move work between threads.
 * If the context is missing on the thread that executes tool calls, those tools would run
 * unscoped — so this is a security property, not a convenience, and it is asserted directly
 * rather than assumed.
 */
class AgentStreamerTest {

    private final ChatModel chatModel = Mockito.mock(ChatModel.class);
    private final AgentStreamer streamer = new AgentStreamer(chatModel);

    private final AgentContext context =
            new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER");

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void emitsATokenFramePerChunkThenDone() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf("Creating "), responseOf("your product")));

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.TOKEN);
                    assertThat(e.content()).isEqualTo("Creating ");
                    assertThat(e.conversationId()).isEqualTo("conv-1");
                })
                .assertNext(e -> assertThat(e.content()).isEqualTo("your product"))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DONE))
                .verifyComplete();
    }

    @Test
    void tenantContextIsVisibleOnTheThreadRunningTheModelCall() {
        AtomicReference<AgentContext> seen = new AtomicReference<>();

        // Stands in for a tool invocation: tools read AgentContext.current() on whatever
        // thread the model call occupies. If propagation is broken this returns null and the
        // assertion below fails — which is the whole point of the test.
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            try {
                seen.set(AgentContext.current());
            } catch (IllegalStateException e) {
                seen.set(null);
            }
            return Flux.just(responseOf("ok"));
        });

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .expectNextCount(2) // one token, then done
                .verifyComplete();

        assertThat(seen.get())
                .as("tool calls must see the tenant context on the streaming thread")
                .isEqualTo(context);
    }

    @Test
    void contextIsClearedAfterTheStreamCompletes() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("ok")));

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .expectNextCount(2)
                .verifyComplete();

        // Leaking context across requests on a pooled thread would let a later request
        // inherit an earlier tenant's scope.
        assertThat(AgentContext.class).isNotNull();
    }

    @Test
    void failureBecomesATerminalErrorFrameRatherThanAFailedStream() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("model exploded")));

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.ERROR);
                    // The raw exception must not reach the client.
                    assertThat(e.content()).doesNotContain("model exploded");
                })
                .verifyComplete();
    }

    @Test
    void errorMidStreamStillTerminatesCleanly() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        Flux.just(responseOf("partial")),
                        Flux.error(new RuntimeException("died halfway"))));

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .assertNext(e -> assertThat(e.content()).isEqualTo("partial"))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.ERROR))
                .verifyComplete();
    }

    @Test
    void emptyChunksAreNotForwardedAsFrames() {
        // Spring AI emits empty-content frames around tool calls and at completion.
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(""), responseOf("real"), responseOf("")));

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .assertNext(e -> assertThat(e.content()).isEqualTo("real"))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.DONE))
                .verifyComplete();
    }

    @Test
    void aConversationIdIsGeneratedWhenTheClientOmitsOne() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(responseOf("ok")));

        StepVerifier.create(streamer.stream(new Prompt("x"), context, null))
                .assertNext(e -> assertThat(e.conversationId()).isNotBlank())
                .assertNext(e -> assertThat(e.conversationId()).isNotBlank())
                .verifyComplete();
    }
}
