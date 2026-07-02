package com.mercala.payment.resilience;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRetryTemplateTest {

    @Test
    void retriesAndSucceedsOnTransientExceptions() {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = PaymentRetryTemplate.execute(
                () -> {
                    int current = attempts.incrementAndGet();
                    if (current < 3) {
                        throw new RuntimeException("connection timeout");
                    }
                    return "success";
                },
                3, 10, 2.0
        );

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void failsImmediatelyOnNonTransientExceptions() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> PaymentRetryTemplate.execute(
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("invalid argument exception");
                },
                3, 10, 2.0
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void failsAfterMaxAttemptsReached() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> PaymentRetryTemplate.execute(
                () -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("connection timeout");
                },
                3, 10, 2.0
        )).isInstanceOf(RuntimeException.class);

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void detectsTransientCauses() {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = PaymentRetryTemplate.execute(
                () -> {
                    int current = attempts.incrementAndGet();
                    if (current < 2) {
                        throw new RuntimeException(new SocketTimeoutException("read timeout"));
                    }
                    return "success";
                },
                3, 10, 2.0
        );

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(2);
    }
}
