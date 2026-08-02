package com.mercala.agent.agui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.agent.chat.AgentContext;
import com.mercala.agent.chat.MerchantAgentService;
import com.mercala.agent.security.AgentGuardrailService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

/**
 * The AG-UI endpoint for the merchant agent.
 *
 * <p>{@code POST /api/agent/merchant/agui} — a run in, a stream of protocol events out.
 *
 * <p>Kept separate from {@code /chat/stream} rather than replacing it. The old endpoint has
 * a different request shape and a different frame vocabulary, and something is using it;
 * migrating a client is a decision for whoever owns that client, not a side effect of
 * adding this one.
 *
 * <h2>Guardrails run before the first frame</h2>
 *
 * <p>Rate limiting and the prompt-injection scan happen here, synchronously, so an abusive
 * request is refused with a 429 or a 400. Deferred into the stream they would arrive as a
 * 200 whose body turned out to be an error — technically an answer, but not one an HTTP
 * client, a proxy, or a metrics dashboard can act on.
 */
@RestController
@RequestMapping("/api/agent/merchant")
public class MerchantAgUiController {

    private static final Logger log = LoggerFactory.getLogger(MerchantAgUiController.class);

    private final MerchantAgentService agentService;
    private final AgentGuardrailService guardrailService;

    public MerchantAgUiController(MerchantAgentService agentService, AgentGuardrailService guardrailService) {
        this.agentService = agentService;
        this.guardrailService = guardrailService;
    }

    @Operation(
            summary = "Run the merchant agent and stream AG-UI events",
            description = """
                    Accepts an AG-UI RunAgentInput and returns Server-Sent Events. Each SSE
                    frame's `event` field is the lowercased AG-UI event type and its `data`
                    field is the event object, whose `type` field carries the canonical
                    uppercase name.

                    The conversation lives on the client: send the whole thread each run,
                    including `tool` messages carrying answers to anything the agent asked
                    on a previous run.
                    """)
    @PostMapping(value = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgUiEvent>> run(@Valid @RequestBody RunAgentInput input) {
        log.info("POST /api/agent/merchant/agui — thread={}, run={}", input.threadId(), input.runId());

        guardrailService.checkRateLimit(currentUserId());
        guardrailService.scanPrompt(input.latestUserMessage());

        return agentService.agui(input)
                .map(event -> ServerSentEvent.<AgUiEvent>builder()
                        .event(event.type().toLowerCase())
                        .data(event)
                        .build());
    }

    /**
     * The authenticated user, or null when there is no ambient context — the test profile
     * skips authentication, and rate limiting a null user is already a no-op, so this needs
     * no branch of its own.
     */
    private static java.util.UUID currentUserId() {
        AgentContext context = AgentContext.currentOrNull();
        return context == null ? null : context.userId();
    }
}
