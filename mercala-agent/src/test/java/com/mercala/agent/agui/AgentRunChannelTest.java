package com.mercala.agent.agui;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The channel is the only path by which a tool's reality reaches the client, so its
 * ordering, its termination and its behaviour under concurrent tools are all load-bearing.
 */
class AgentRunChannelTest {

    @Test
    void toolLifecycleIsEmittedInProtocolOrder() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");

        channel.toolStarted("tc1", "createProduct", "{\"name\":\"Linen shirt\"}");
        channel.toolFinished("tc1", "{\"id\":\"p1\"}", 42L, false);
        channel.close();

        List<AgUiEvent> events = channel.events().collectList().block();
        assertThat(events).isNotNull();

        List<String> types = events.stream().map(AgUiEvent::type).toList();
        assertThat(types).containsSubsequence(
                AgUiEventType.TOOL_CALL_START,
                AgUiEventType.TOOL_CALL_ARGS,
                AgUiEventType.TOOL_CALL_END,
                AgUiEventType.TOOL_CALL_RESULT);
    }

    @Test
    void argumentsFrameIsSkippedWhenThereAreNone() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");

        channel.toolStarted("tc1", "listStores", "   ");
        channel.close();

        assertThat(channel.events().collectList().block())
                .noneMatch(event -> AgUiEventType.TOOL_CALL_ARGS.equals(event.type()));
    }

    @Test
    void activityStateDeltasTrackTheCallFromRunningToDone() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");

        channel.toolStarted("tc1", "createProduct", "{}");
        channel.toolFinished("tc1", "{}", 7L, false);
        channel.close();

        List<AgUiEvent> events = channel.events().collectList().block();
        assertThat(events).isNotNull();

        List<Object> patchPaths = events.stream()
                .filter(AgUiEvent.StateDelta.class::isInstance)
                .map(AgUiEvent.StateDelta.class::cast)
                .flatMap(delta -> delta.delta().stream())
                .map(patch -> patch.get("path"))
                .toList();

        assertThat(patchPaths).contains("/activity/tc1", "/activity/tc1/status", "/activity/tc1/durationMs");
    }

    @Test
    void aFailedToolIsReportedAsFailedRatherThanDropped() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");

        channel.toolStarted("tc1", "updateInventory", "{}");
        channel.toolFinished("tc1", "{\"status\":\"FAILED\"}", 3L, true);
        channel.close();

        List<AgUiEvent> events = channel.events().collectList().block();
        assertThat(events).isNotNull();

        AgUiEvent.ToolCallResult result = events.stream()
                .filter(AgUiEvent.ToolCallResult.class::isInstance)
                .map(AgUiEvent.ToolCallResult.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(result.error()).isTrue();
        assertThat(result.durationMs()).isEqualTo(3L);
    }

    /**
     * A cancelled SSE connection closes the channel while tools are still running. Those
     * late emissions must be dropped rather than retried, or the worker running the tool
     * spins against a terminated sink for as long as the tool lives.
     */
    @Test
    void emissionAfterCloseIsDroppedWithoutThrowing() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");
        channel.close();

        channel.toolStarted("tc1", "createProduct", "{}");
        channel.toolFinished("tc1", "{}", 1L, false);

        assertThat(channel.events().collectList().block()).isEmpty();
    }

    @Test
    void anInactiveChannelSwallowsEverythingAndStillYieldsIds() {
        AgentRunChannel channel = AgentRunChannel.inactive();

        assertThat(channel.isActive()).isFalse();
        assertThat(channel.nextToolCallId()).isNotBlank();

        channel.toolStarted("tc1", "createProduct", "{}");
        channel.custom("thing", java.util.Map.of());
        channel.close();

        assertThat(channel.events().collectList().block()).isEmpty();
    }

    @Test
    void toolCallIdsAreUniquePerCall() {
        AgentRunChannel channel = AgentRunChannel.active("run-1");

        assertThat(channel.nextToolCallId()).isNotEqualTo(channel.nextToolCallId());
    }

    /**
     * Parallel tool calls emit from different threads at once, and a Reactor unicast sink
     * is not safe against that on its own.
     */
    @Test
    void concurrentToolsDoNotLoseEvents() throws Exception {
        AgentRunChannel channel = AgentRunChannel.active("run-1");
        int tools = 8;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(tools);

        for (int i = 0; i < tools; i++) {
            String id = "tc" + i;
            pool.submit(() -> {
                try {
                    channel.toolStarted(id, "searchCatalog", "{}");
                    channel.toolFinished(id, "{}", 1L, false);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        channel.close();

        List<AgUiEvent> events = channel.events().collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.stream().filter(AgUiEvent.ToolCallStart.class::isInstance)).hasSize(tools);
        assertThat(events.stream().filter(AgUiEvent.ToolCallResult.class::isInstance)).hasSize(tools);
    }
}
