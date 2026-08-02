package com.mercala.agent.agui;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format is the contract with a TypeScript client that switches on
 * {@code event.type}. A field renamed here and not there produces a client that silently
 * ignores half the run, so the JSON is asserted directly rather than through the Java types.
 */
class AgUiEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(AgUiEvent event) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(event));
    }

    @Test
    void everyEventCarriesItsTypeDiscriminator() throws Exception {
        List<AgUiEvent> all = List.of(
                new AgUiEvent.RunStarted("t", "r"),
                new AgUiEvent.RunFinished("t", "r", Map.of()),
                new AgUiEvent.RunError("t", "r", "boom", "AGENT_ERROR"),
                new AgUiEvent.StepStarted("model-turn"),
                new AgUiEvent.StepFinished("model-turn"),
                new AgUiEvent.TextMessageStart("m1"),
                new AgUiEvent.TextMessageContent("m1", "hi"),
                new AgUiEvent.TextMessageEnd("m1"),
                new AgUiEvent.ToolCallStart("tc1", "createProduct", null),
                new AgUiEvent.ToolCallArgs("tc1", "{}"),
                new AgUiEvent.ToolCallEnd("tc1"),
                new AgUiEvent.ToolCallResult("tr1", "tc1", "{}", "tool", 12L, null),
                new AgUiEvent.StateSnapshot(Map.of("phase", "thinking")),
                new AgUiEvent.StateDelta(List.of(Map.of("op", "replace", "path", "/phase", "value", "working"))),
                new AgUiEvent.MessagesSnapshot(List.of(AgUiMessage.user("hello"))),
                new AgUiEvent.Custom("image_job", Map.of("phase", "queued")),
                new AgUiEvent.Raw(Map.of("k", "v"), "upstream"));

        for (AgUiEvent event : all) {
            JsonNode node = json(event);
            assertThat(node.hasNonNull("type"))
                    .as("%s must serialise a type discriminator", event.getClass().getSimpleName())
                    .isTrue();
            assertThat(node.get("type").asText()).isEqualTo(event.type());
        }
    }

    @Test
    void textContentUsesTheProtocolFieldNames() throws Exception {
        JsonNode node = json(new AgUiEvent.TextMessageContent("m1", "Creating "));

        assertThat(node.get("type").asText()).isEqualTo("TEXT_MESSAGE_CONTENT");
        assertThat(node.get("messageId").asText()).isEqualTo("m1");
        assertThat(node.get("delta").asText()).isEqualTo("Creating ");
    }

    @Test
    void toolCallResultCarriesDurationAndErrorFlag() throws Exception {
        JsonNode node = json(new AgUiEvent.ToolCallResult("tr1", "tc1", "{\"status\":\"FAILED\"}", "tool", 340L, true));

        assertThat(node.get("toolCallId").asText()).isEqualTo("tc1");
        assertThat(node.get("durationMs").asLong()).isEqualTo(340L);
        assertThat(node.get("error").asBoolean()).isTrue();
    }

    /**
     * A successful result has no error flag at all rather than {@code "error": false}. The
     * client tests for presence, and an explicit false is a third state it would have to
     * learn about.
     */
    @Test
    void nullsAreOmittedRatherThanSerialisedAsNull() throws Exception {
        JsonNode node = json(new AgUiEvent.ToolCallResult("tr1", "tc1", "{}", "tool", 5L, null));

        assertThat(node.has("error")).isFalse();
    }

    @Test
    void textMessageStartDefaultsToTheAssistantRole() throws Exception {
        assertThat(json(new AgUiEvent.TextMessageStart("m1")).get("role").asText()).isEqualTo("assistant");
    }
}
