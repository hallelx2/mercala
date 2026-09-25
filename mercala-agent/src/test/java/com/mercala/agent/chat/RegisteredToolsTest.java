package com.mercala.agent.chat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import com.mercala.agent.tool.CatalogTools;
import com.mercala.agent.tool.HitlTools;
import com.mercala.agent.tool.ImageTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tool the agent can call must be named in a tool set.
 *
 * <p>Spring AI is told which functions to offer by name. A tool bean missing from that set
 * is worse than unavailable: the model is still shown the function, calls it, and Spring AI
 * throws "No function callback found" midway through the stream. The merchant sees "the
 * assistant could not complete this request" and nothing else — no failing tool, no clue.
 * That is how listProducts shipped broken, so the coupling gets a test rather than a
 * comment.
 */
class RegisteredToolsTest {

    private static final Set<Class<?>> TOOL_CONFIGURATIONS =
            Set.of(CatalogTools.class, HitlTools.class, ImageTools.class);

    @Test
    void everyToolBeanIsOfferedToAtLeastOneAgent() {
        Set<String> offered = new HashSet<>(MerchantAgentService.AGENTIC_TOOLS);
        offered.addAll(MerchantAgentService.MERCHANT_TOOLS);
        offered.addAll(ShopperAgentService.SHOPPER_TOOLS);

        assertThat(toolBeanNames()).allSatisfy(name ->
                assertThat(offered).as("tool '%s' is defined but offered to no agent", name).contains(name));
    }

    /**
     * And the reverse: a name in a set with no bean behind it fails the same way, and is
     * what a rename leaves behind.
     */
    @Test
    void everyOfferedToolNameHasABeanBehindIt() {
        Set<String> beans = toolBeanNames();
        Set<String> serverSide = new HashSet<>(MerchantAgentService.AGENTIC_TOOLS);
        serverSide.addAll(ShopperAgentService.SHOPPER_TOOLS);

        assertThat(serverSide).allSatisfy(name ->
                assertThat(beans).as("tool '%s' is offered but no @Bean defines it", name).contains(name));
    }

    private static Set<String> toolBeanNames() {
        return TOOL_CONFIGURATIONS.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> Function.class.isAssignableFrom(method.getReturnType()))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
