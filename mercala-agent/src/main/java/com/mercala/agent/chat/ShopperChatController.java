package com.mercala.agent.chat;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for shopper discovery interactions with the Mercala agent.
 *
 * POST /api/agent/shopper/chat
 *   - Receives a ChatRequest (message, tenantId, userId, conversationId)
 *   - Returns a ChatResponse with grounded product recommendations
 */
@RestController
@RequestMapping("/api/agent/shopper")
public class ShopperChatController {

    private static final Logger log = LoggerFactory.getLogger(ShopperChatController.class);

    private final ShopperAgentService agentService;
    private final com.mercala.agent.security.AgentGuardrailService guardrailService;

    public ShopperChatController(
            ShopperAgentService agentService,
            com.mercala.agent.security.AgentGuardrailService guardrailService) {
        this.agentService = agentService;
        this.guardrailService = guardrailService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("POST /api/agent/shopper/chat — tenant={}", request.tenantId());

        // Enforce security guardrails
        guardrailService.checkRateLimit(request.userId());
        guardrailService.scanPrompt(request.message());

        ChatResponse response = agentService.chat(request);

        return ResponseEntity.ok(response);
    }
}
