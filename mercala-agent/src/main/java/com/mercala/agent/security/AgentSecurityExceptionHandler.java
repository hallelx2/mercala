package com.mercala.agent.security;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.mercala.agent.security.AgentGuardrailService.RateLimitExceededException;

@ControllerAdvice
public class AgentSecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentSecurityExceptionHandler.class);

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handleSecurityException(SecurityException ex) {
        log.warn("Security rejection handled: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "Security violation",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimitException(RateLimitExceededException ex) {
        log.warn("Rate limit exception handled: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "error", "Too Many Requests",
                        "message", ex.getMessage()
                ));
    }
}
