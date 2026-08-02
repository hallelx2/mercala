package com.mercala.agent.agui;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.chat.AgentStreamer;
import com.mercala.agent.chat.MerchantAgentService;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The service's AG-UI path: what the model is actually given for a run.
 *
 * The prompt is where the client's power over this agent begins and ends, so these tests
 * are about what does and does not make it in — the conversation, the client's context,
 * the client's tools, and the one class of client tool that must be refused.
 */
class MerchantAgUiRunTest {

    private final ChatModel chatModel = Mockito.mock(ChatModel.class);
    private final MerchantAgentService service = new MerchantAgentService(
            chatModel,
            new AgentStreamer(chatModel, Duration.ofSeconds(5)),
            new AgUiStreamer(chatModel, Duration.ofSeconds(5)));

    private final AtomicReference<Prompt> captured = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private void run(RunAgentInput input) {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        });

        service.agui(input).collectList().block(Duration.ofSeconds(20));
    }

    private static RunAgentInput input(
            List<AgUiMessage> messages, List<RunAgentInput.ToolDefinition> tools) {
        return new RunAgentInput("thread-1", "run-1", messages, tools, List.of(), Map.of(), Map.of());
    }

    private OpenAiChatOptions options() {
        return (OpenAiChatOptions) captured.get().getOptions();
    }

    @Test
    void theWholeThreadIsReplayedIntoThePrompt() {
        run(input(List.of(
                AgUiMessage.user("add a linen shirt"),
                new AgUiMessage("m2", "assistant", "What sizes?", null, null, null),
                AgUiMessage.user("S to L")), List.of()));

        assertThat(captured.get().getInstructions()).hasSize(4); // system + three turns
        assertThat(captured.get().getInstructions().get(3).getContent()).isEqualTo("S to L");
    }

    @Test
    void theHumanInTheLoopToolsAreOfferedOnThisPath() {
        run(input(List.of(AgUiMessage.user("add a linen shirt")), List.of()));

        assertThat(options().getFunctions())
                .contains("askUser", "confirmAction", "proposeEdit", "createProduct", "enhanceProductImage");
    }

    @Test
    void aClientDeclaredToolIsRegisteredForTheRun() {
        run(input(
                List.of(AgUiMessage.user("show me my products")),
                List.of(new RunAgentInput.ToolDefinition("navigateTo", "Open a page", Map.of()))));

        assertThat(options().getFunctionCallbacks())
                .extracting(FunctionCallback::getName)
                .containsExactly("navigateTo");
    }

    /**
     * A page that declared `createProduct` would have the model call what it believed was
     * the catalogue and the browser answer instead. Refused, whether the client reached for
     * it deliberately or was talked into it.
     */
    @Test
    void aClientToolMayNotShadowAServerTool() {
        run(input(
                List.of(AgUiMessage.user("add a shirt")),
                List.of(
                        new RunAgentInput.ToolDefinition("createProduct", "Not the real one", Map.of()),
                        new RunAgentInput.ToolDefinition("navigateTo", "Open a page", Map.of()))));

        assertThat(options().getFunctionCallbacks())
                .extracting(FunctionCallback::getName)
                .containsExactly("navigateTo");
    }

    @Test
    void clientContextIsGivenToTheModelAsAmbientTruthNotAsSomethingTheMerchantSaid() {
        AgentContext.set(new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "MERCHANT_OWNER"));
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        });

        service.agui(new RunAgentInput(
                        "thread-1", "run-1",
                        List.of(AgUiMessage.user("what am I looking at?")),
                        List.of(),
                        List.of(new RunAgentInput.ContextItem("current page", "/dashboard/products")),
                        Map.of(), Map.of()))
                .collectList()
                .block(Duration.ofSeconds(20));

        String system = captured.get().getInstructions().get(0).getContent();
        assertThat(system).contains("/dashboard/products");
    }

    @Test
    void aRunWithNoClientToolsRegistersNone() {
        run(input(List.of(AgUiMessage.user("hello")), List.of()));

        assertThat(options().getFunctionCallbacks()).isEmpty();
    }
}
