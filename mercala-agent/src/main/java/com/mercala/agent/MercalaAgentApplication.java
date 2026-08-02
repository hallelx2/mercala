package com.mercala.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.mercala.agent.chat.AgentContextAccessor;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class MercalaAgentApplication {

    public static void main(String[] args) {
        enableContextPropagation();
        SpringApplication.run(MercalaAgentApplication.class, args);
    }

    /**
     * Makes {@code AgentContext} survive the thread hops inside Spring AI's streaming
     * pipeline (HAL-515). Must run before any Flux is assembled, hence before the
     * application context starts rather than in a {@code @Bean}. Idempotent so tests can
     * call it too: re-registering the accessor replaces the previous one by key, and the
     * Reactor hook is a global flag.
     */
    public static void enableContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new AgentContextAccessor());
        Hooks.enableAutomaticContextPropagation();
    }
}
