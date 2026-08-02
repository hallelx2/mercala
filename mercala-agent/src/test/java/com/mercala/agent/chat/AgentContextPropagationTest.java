package com.mercala.agent.chat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mercala.agent.MercalaAgentApplication;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the HAL-515 fix: {@link AgentContext} must be visible on threads Reactor moves work
 * to, because Spring AI runs tool callbacks off the subscribing worker and the core client
 * derives its tenant header from this ThreadLocal.
 *
 * <p>The tests model the failure directly — {@code publishOn} forces the read onto a
 * different scheduler thread, exactly the hop that produced "AgentContext not available"
 * and the downstream 401 in production. One test proves the fix works; the other proves
 * the test can fail, by showing the context does NOT cross the hop when the pipeline
 * lacks the {@code contextWrite}.
 */
class AgentContextPropagationTest {

    @BeforeAll
    static void enablePropagation() {
        // Same call main() makes — the test exercises the production wiring, not a copy.
        MercalaAgentApplication.enableContextPropagation();
    }

    private static final AgentContext CONTEXT =
            new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT");

    /** Reads the ThreadLocal on whatever thread the operator lands on. */
    private static Flux<String> readContextAcrossThreadHop() {
        return Flux.just("tool-call")
                .publishOn(Schedulers.single())
                .map(ignored -> {
                    AgentContext seen = AgentContext.currentOrNull();
                    return seen != null ? seen.tenantId().toString() : "MISSING";
                });
    }

    @Test
    void contextWrittenToReactorContextIsRestoredOnForeignThreads() {
        StepVerifier.create(
                        readContextAcrossThreadHop()
                                .contextWrite(ctx -> ctx.put(AgentContextAccessor.KEY, CONTEXT)))
                .expectNext(CONTEXT.tenantId().toString())
                .expectComplete()
                    .verify(Duration.ofSeconds(5));
    }

    /**
     * The control: with automatic propagation off — the pre-fix world — the hop loses the
     * context even when the subscribing thread had it set. This is the production bug
     * reproduced, and the proof that the passing test above measures the fix rather than
     * passing vacuously. (An earlier version of this control expected the loss with the
     * hook still on; it failed, because the hook alone already carries a ThreadLocal set
     * at subscription time across the hop — that is precisely what the fix provides.)
     */
    @Test
    void withoutThePropagationHookTheThreadHopLosesTheContext() {
        Hooks.disableAutomaticContextPropagation();
        AgentContext.set(CONTEXT);
        try {
            StepVerifier.create(readContextAcrossThreadHop())
                    .expectNext("MISSING")
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        } finally {
            AgentContext.clear();
            // Restore the production wiring for any test that runs after this one.
            MercalaAgentApplication.enableContextPropagation();
        }
    }

    @Test
    void accessorReadsAndWritesTheThreadLocal() {
        AgentContextAccessor accessor = new AgentContextAccessor();
        assertThat(accessor.getValue()).isNull();

        accessor.setValue(CONTEXT);
        assertThat(AgentContext.currentOrNull()).isEqualTo(CONTEXT);

        accessor.setValue();
        assertThat(accessor.getValue()).isNull();
    }
}
