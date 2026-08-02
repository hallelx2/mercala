package com.mercala.agent.chat;

import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for merchant chat interactions with the Mercala agent.
 * 
 * POST /api/agent/merchant/chat
 *   - Receives a ChatRequest (message, tenantId, userId, conversationId)
 *   - Returns a ChatResponse (agent reply, tools used, timestamp)
 */
@RestController
@RequestMapping("/api/agent/merchant")
public class MerchantChatController {

    private static final Logger log = LoggerFactory.getLogger(MerchantChatController.class);

    private final MerchantAgentService agentService;
    private final com.mercala.agent.security.AgentGuardrailService guardrailService;

    public MerchantChatController(
            MerchantAgentService agentService,
            com.mercala.agent.security.AgentGuardrailService guardrailService) {
        this.agentService = agentService;
        this.guardrailService = guardrailService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/agent/merchant/chat — tenant={}", request.tenantId());

        // Enforce security guardrails
        guardrailService.checkRateLimit(request.userId());
        guardrailService.scanPrompt(request.message());

        ChatResponse response = agentService.chat(request);

        return ResponseEntity.ok(response);
    }

    /**
     * Streaming variant. Same guardrails as the JSON endpoint — rate limit and prompt scan
     * run before a single frame is emitted, so an abusive request is rejected with a proper
     * status rather than a 200 whose body turns out to be an error frame.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/agent/merchant/chat/stream — tenant={}", request.tenantId());

        guardrailService.checkRateLimit(request.userId());
        guardrailService.scanPrompt(request.message());

        return agentService.chatStream(request)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type().name().toLowerCase())
                        .data(event)
                        .build());
    }
}
