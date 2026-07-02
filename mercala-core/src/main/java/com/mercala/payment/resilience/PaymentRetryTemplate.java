package com.mercala.payment.resilience;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PaymentRetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryTemplate.class);

    public static <T> T execute(Supplier<T> action, int maxAttempts, long initialBackoffMs, double multiplier) {
        int attempt = 0;
        long backoff = initialBackoffMs;
        
        while (true) {
            try {
                attempt++;
                return action.get();
            } catch (Exception e) {
                if (attempt >= maxAttempts || !isTransientException(e)) {
                    log.error("Payment execution failed after {} attempts", attempt, e);
                    throw e;
                }
                
                log.warn("Payment attempt {} failed with transient error: {}. Retrying in {}ms...", 
                        attempt, e.getMessage(), backoff);
                
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Payment retry interrupted", ie);
                }
                
                backoff = (long) (backoff * multiplier);
            }
        }
    }

    private static boolean isTransientException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("connect") || msg.contains("connection refused") 
                || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("408")
                || msg.contains("network error") || msg.contains("socket")) {
            return true;
        }
        Throwable cause = e.getCause();
        if (cause instanceof java.io.IOException || cause instanceof java.net.SocketTimeoutException) {
            return true;
        }
        return false;
    }
}
