package com.mercala.agent.security;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardrailService.class);

    // Simple thread-safe rate limiter storage: User ID -> TokenBucket
    private final ConcurrentHashMap<UUID, TokenBucket> rateLimits = new ConcurrentHashMap<>();

    @Value("${mercala.guardrails.rate-limit.capacity:10}")
    private int limitCapacity;

    @Value("${mercala.guardrails.rate-limit.refill-rate-seconds:2}")
    private int refillRateSeconds;

    // Regexp patterns for prompt injection detection
    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s+override", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+in\\s+(god|developer|admin)\\s+mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("bypass\\s+tenant", Pattern.CASE_INSENSITIVE),
            Pattern.compile("execute\\s+arbitrary\\s+code", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reveal\\s+(your\\s+)?system\\s+prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("acting\\s+as\\s+a\\s+super\\s*user", Pattern.CASE_INSENSITIVE)
    };

    /**
     * Scans an incoming message for known prompt-injection patterns.
     * Throws a SecurityException if an injection pattern is detected.
     */
    public void scanPrompt(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(message).find()) {
                log.warn("Security Alert: Prompt injection attempt detected! Pattern matched: '{}'", pattern.pattern());
                throw new SecurityException("Request rejected due to potential prompt injection detection.");
            }
        }
    }

    /**
     * Checks rate limits for a given user.
     * Throws a RateLimitExceededException if rate limit is exceeded.
     */
    public void checkRateLimit(UUID userId) {
        if (userId == null) {
            return;
        }

        TokenBucket bucket = rateLimits.computeIfAbsent(userId, id -> new TokenBucket(limitCapacity, refillRateSeconds));
        if (!bucket.tryConsume()) {
            log.warn("Rate limit exceeded for user: {}", userId);
            throw new RateLimitExceededException("Too many requests. Please slow down.");
        }
    }

    private static class TokenBucket {
        private final double capacity;
        private final double refillRatePerSecond;
        private double tokens;
        private Instant lastRefill;

        public TokenBucket(int capacity, int refillRateSeconds) {
            this.capacity = capacity;
            this.refillRatePerSecond = 1.0 / refillRateSeconds;
            this.tokens = capacity;
            this.lastRefill = Instant.now();
        }

        public synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            Instant now = Instant.now();
            double secondsPassed = java.time.Duration.between(lastRefill, now).toNanos() / 1_000_000_000.0;
            if (secondsPassed > 0) {
                tokens = Math.min(capacity, tokens + (secondsPassed * refillRatePerSecond));
                lastRefill = now;
            }
        }
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
