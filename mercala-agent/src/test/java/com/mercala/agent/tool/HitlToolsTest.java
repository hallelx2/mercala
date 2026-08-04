package com.mercala.agent.tool;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mercala.agent.tool.ToolPayloads.AskFieldArg;
import com.mercala.agent.tool.ToolPayloads.AskUserArgs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client renders one shape. These tests exist because the model does not send one.
 *
 * <p>A model reaches for the simplest schema that looks like it will work, so the older
 * single-question form keeps arriving even with the richer one documented — and a field
 * type it invented arrives occasionally too. Normalising here is what keeps the question
 * card from carrying a branch per shape the model happened to pick.
 */
class HitlToolsTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fieldsOf(AskUserArgs args) {
        Map<String, Object> result = new HitlTools().askUser().apply(args);
        Map<String, Object> payload = (Map<String, Object>) result.get("payload");
        return (List<Map<String, Object>>) payload.get("fields");
    }

    @Test
    void aFormOfSeveralFieldsSurvivesIntact() {
        var fields = fieldsOf(new AskUserArgs("A few details first", List.of(
                new AskFieldArg("name", "Product name", "text", null, "Nutty Roots", false, null),
                new AskFieldArg("price", "Price", "money", null, "24.99", false, null),
                new AskFieldArg("photo", "A photograph", "image", null, null, true, null)), null, null));

        assertThat(fields).hasSize(3);
        assertThat(fields.get(1)).containsEntry("type", "money").containsEntry("optional", false);
        assertThat(fields.get(2)).containsEntry("type", "image").containsEntry("optional", true);
    }

    /** The shape a model sends when it has read only half the description. */
    @Test
    void aBareQuestionWithOptionsBecomesAOneFieldForm() {
        var fields = fieldsOf(new AskUserArgs(
                "Is this a drink or a powder?", null, List.of("Ready to drink", "Powder"), null));

        assertThat(fields).hasSize(1);
        assertThat(fields.get(0))
                .containsEntry("label", "Is this a drink or a powder?")
                .containsEntry("type", "choice")
                .containsEntry("options", List.of("Ready to drink", "Powder"))
                .containsEntry("optional", false)
                .containsEntry("allowFreeText", true);
    }

    @Test
    void aBareQuestionWithNoOptionsBecomesATextField() {
        var fields = fieldsOf(new AskUserArgs("What should it be called?", null, null, null));

        assertThat(fields.get(0)).containsEntry("type", "text").containsEntry("options", List.of());
    }

    /**
     * The only way to say "one of these and nothing else" — and the only case where the
     * merchant is not offered a way round the model's guess at the options.
     */
    @Test
    void allowFreeTextFalseIsCarriedThrough() {
        var fields = fieldsOf(new AskUserArgs(
                "Which one?", null, List.of("This", "That"), false));

        assertThat(fields.get(0)).containsEntry("allowFreeText", false);
    }

    @Test
    void anInventedFieldTypeFallsBackRatherThanBreakingTheCard() {
        var fields = fieldsOf(new AskUserArgs("Details", List.of(
                new AskFieldArg("email", "Contact", "email", null, null, null, null),
                new AskFieldArg("size", "Size", "dropdown", List.of("S", "M"), null, null, null)),
                null, null));

        // No options to fall back on, so a plain input.
        assertThat(fields.get(0)).containsEntry("type", "text");
        // Options were given, so the intent was clearly a choice.
        assertThat(fields.get(1)).containsEntry("type", "choice");
    }

    @Test
    void aFieldWithOnlyALabelStillHasANameToAnswerUnder() {
        var fields = fieldsOf(new AskUserArgs("Details", List.of(
                new AskFieldArg(null, "Product name", "text", null, null, null, null)), null, null));

        assertThat(fields.get(0)).containsEntry("name", "Product name");
    }

    /**
     * Options are usually the model guessing at a merchant's catalogue, so a choice is open
     * unless it was closed on purpose. Being held to a wrong guess is worse than being
     * offered a right one.
     */
    @Test
    void aChoiceIsOpenUnlessTheAgentDeliberatelyClosedIt() {
        var fields = fieldsOf(new AskUserArgs("Details", List.of(
                new AskFieldArg("size", "Size", "choice", List.of("S", "M"), null, null, null),
                new AskFieldArg("colour", "Colour", "choice", List.of("Navy"), null, null, true)),
                null, null));

        assertThat(fields.get(0)).containsEntry("allowFreeText", true);
        assertThat(fields.get(1)).containsEntry("allowFreeText", true);
    }

    /** For a set that really is closed — a status enum, not a guess at what someone stocks. */
    @Test
    void aStructuredChoiceCanBeClosed() {
        var fields = fieldsOf(new AskUserArgs("Details", List.of(
                new AskFieldArg("status", "Status", "choice", List.of("ACTIVE", "DRAFT"),
                        null, null, false)), null, null));

        assertThat(fields.get(0)).containsEntry("allowFreeText", false);
    }

    /** Everything here ends the run rather than blocking a worker on a human. */
    @Test
    void theRunIsToldToStopAndWait() {
        Map<String, Object> result =
                new HitlTools().askUser().apply(new AskUserArgs("Anything?", null, null, null));

        assertThat(result)
                .containsEntry("status", "AWAITING_USER")
                .containsEntry("kind", "question")
                .containsEntry("awaitingUser", true);
        assertThat((String) result.get("instruction")).contains("Stop here");
    }
}
