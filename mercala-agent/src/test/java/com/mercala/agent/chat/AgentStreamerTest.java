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
import reactor.core.scheduler.Schedulers;
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
    private final AgentStreamer streamer = new AgentStreamer(chatModel, java.time.Duration.ofSeconds(30));

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

    /**
     * The previous version of this test asserted {@code assertThat(AgentContext.class)
     * .isNotNull()}, which compares a class literal against null and therefore always
     * passed. It verified nothing while carrying a name implying it guarded against context
     * leaking onto a pooled worker.
     *
     * <p>This one asks the worker thread itself, after the stream has finished, whether the
     * ThreadLocal is still set — which is the actual property.
     */
    @Test
    void contextIsClearedOnTheWorkerThreadAfterTheStreamCompletes() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            return Flux.just(responseOf("ok"));
        });

        StepVerifier.create(streamer.stream(new Prompt("x"), context, "conv-1"))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(worker.get()).as("the model call should have run on a worker").isNotNull();

        // Ask that exact thread whether it still holds a context. boundedElastic reuses
        // workers, so a leftover value here would be inherited by a later request.
        AtomicReference<Boolean> stillSet = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Schedulers.boundedElastic().schedule(() -> {
            try {
                if (Thread.currentThread() == worker.get()) {
                    try {
                        AgentContext.current();
                        stillSet.set(true);
                    } catch (IllegalStateException expected) {
                        stillSet.set(false);
                    }
                }
            } finally {
                done.countDown();
            }
        });
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);

        // Null means the scheduler handed us a different worker, which is not a failure.
        if (stillSet.get() != null) {
            assertThat(stillSet.get())
                    .as("the worker must not retain the tenant context after the turn ends")
                    .isFalse();
        }
    }

    @Test
    void stalledUpstreamIsBoundedByATimeout() {
        // Never emits and never completes. Without a timeout the worker is occupied
        // forever and boundedElastic eventually starves.
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        AgentStreamer shortTimeout = new AgentStreamer(chatModel, java.time.Duration.ofMillis(150));

        StepVerifier.create(shortTimeout.stream(new Prompt("x"), context, "conv-1"))
                .assertNext(e -> assertThat(e.type()).isEqualTo(ChatStreamEvent.Type.ERROR))
                .verifyComplete();
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
