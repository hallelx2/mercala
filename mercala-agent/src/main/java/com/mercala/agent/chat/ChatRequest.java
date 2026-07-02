package com.mercala.agent.chat;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Inbound payload from the merchant's chat interface.
 */
public record ChatRequest(
        @NotBlank(message = "Message is required")
        String message,

        UUID tenantId,

        UUID userId,

        String conversationId
) {}
