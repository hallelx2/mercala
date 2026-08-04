package com.mercala.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.mercala.agent.tool.ToolPayloads.AskUserArgs;
import com.mercala.agent.tool.ToolPayloads.ConfirmActionArgs;
import com.mercala.agent.tool.ToolPayloads.ProposeEditArgs;

/**
 * Tools whose entire job is to hand the turn back to the merchant.
 *
 * <h2>Why these are tools at all</h2>
 *
 * <p>A model that cannot ask guesses instead. "Add a linen shirt, £49" leaves the sizes,
 * the colour and the SKU scheme unstated, and an agent with no way to ask will invent all
 * three and then create a product built on the invention. Giving it {@code askUser} makes
 * asking a first-class move rather than something it has to smuggle into prose that the
 * client has no way to render as a control.
 *
 * <h2>How the round trip closes</h2>
 *
 * <p>These do not block. Blocking a tool call until a human answers would hold a
 * {@code boundedElastic} worker and an HTTP response open for however long the merchant
 * takes to look up a price — minutes, or forever if they close the tab.
 *
 * <p>Instead the tool returns a sentinel: the model is told the question has been put to
 * the merchant and that it should stop, and the {@link ToolActivity} wrapper has already
 * emitted the tool call the client renders as a control. The run ends normally. When the
 * merchant answers, the client appends a {@code tool} message carrying the real answer
 * against that {@code toolCallId} and starts a new run. The model then reads the
 * conversation as: I asked, and the answer was "navy, sizes S–L".
 */
@Configuration
public class HitlTools {

    private static final Logger log = LoggerFactory.getLogger(HitlTools.class);

    /** What the model is told after a question is dispatched. Phrased as an instruction because it is one. */
    private static final String AWAIT_INSTRUCTION =
            "The merchant has been shown this and has not answered yet. Stop here. "
                    + "Do not call another tool, do not assume an answer, and do not act as though one was given. "
                    + "Reply with one short sentence acknowledging that you are waiting.";

    @Bean
    @Description("Asks the merchant for everything you still need, as a form they fill in. "
            + "Put EVERY missing detail in the `fields` list of a SINGLE call — never ask one thing, "
            + "and never write questions as prose, because prose is not something the merchant can "
            + "fill in. Each field has a name, a label, and a type: text, textarea, number, money, "
            + "choice (supply `options`) or image (the merchant uploads a photo). Mark a field "
            + "optional when you can proceed without it. The merchant's answers arrive as a tool "
            + "result on the next turn.")
    public Function<AskUserArgs, Map<String, Object>> askUser() {
        return args -> ToolActivity.observe("askUser", args, () -> {
            List<Map<String, Object>> fields = normalise(args);
            log.info("Tool: askUser invoked — question='{}', fields={}", args.question(), fields.size());
            return awaiting("question", Map.of(
                    "question", nullSafe(args.question()),
                    "fields", fields));
        });
    }

    /**
     * One shape for the client, whichever shape the model sent.
     *
     * <p>Models reach for the simplest schema that looks like it will work, so a single
     * question with a list of options keeps arriving even when the richer one is documented.
     * Normalising here means the card renders a form either way, rather than the client
     * carrying two code paths for what is the same question with one field in it.
     */
    private static List<Map<String, Object>> normalise(AskUserArgs args) {
        if (args.fields() != null && !args.fields().isEmpty()) {
            return args.fields().stream().map(HitlTools::field).toList();
        }

        // The single-question shape: the question is the field, and its options are the
        // choices. allowFreeText=false is the only way to say "one of these, nothing else".
        boolean freeText = args.allowFreeText() == null || args.allowFreeText();
        List<String> options = args.options() == null ? List.of() : args.options();
        Map<String, Object> only = new LinkedHashMap<>();
        only.put("name", "answer");
        only.put("label", nullSafe(args.question()));
        only.put("type", options.isEmpty() ? "text" : "choice");
        only.put("options", options);
        only.put("placeholder", "");
        only.put("optional", false);
        only.put("allowFreeText", freeText);
        return List.of(only);
    }

    private static Map<String, Object> field(ToolPayloads.AskFieldArg arg) {
        Map<String, Object> out = new LinkedHashMap<>();
        String label = nullSafe(arg.label()).isBlank() ? nullSafe(arg.name()) : nullSafe(arg.label());
        out.put("name", nullSafe(arg.name()).isBlank() ? label : nullSafe(arg.name()));
        out.put("label", label);
        out.put("type", type(arg));
        out.put("options", arg.options() == null ? List.of() : arg.options());
        out.put("placeholder", nullSafe(arg.placeholder()));
        out.put("optional", Boolean.TRUE.equals(arg.optional()));
        // Open unless the model closed it deliberately. The default matters: options are
        // usually the model guessing at the merchant's catalogue, and being held to a wrong
        // guess is worse than being offered a right one. Closing it is for the cases where
        // the set really is closed — a status enum, not a guess at what sizes a shirt comes in.
        out.put("allowFreeText", !Boolean.FALSE.equals(arg.allowFreeText()));
        return out;
    }

    private static final Set<String> FIELD_TYPES =
            Set.of("text", "textarea", "number", "money", "choice", "image");

    /**
     * An unrecognised type becomes a text box rather than an error. The model inventing
     * {@code "email"} should cost the merchant a plain input, not a broken card.
     */
    private static String type(ToolPayloads.AskFieldArg arg) {
        String declared = arg.type() == null ? "" : arg.type().trim().toLowerCase(java.util.Locale.ROOT);
        if (FIELD_TYPES.contains(declared)) {
            return declared;
        }
        return arg.options() != null && !arg.options().isEmpty() ? "choice" : "text";
    }

    @Bean
    @Description("Asks the merchant to approve an action before it is taken. Use this for anything "
            + "destructive, expensive or hard to undo — deleting, bulk price changes, publishing. "
            + "Describe exactly what will happen. The approval or rejection arrives as a tool result "
            + "on the next turn.")
    public Function<ConfirmActionArgs, Map<String, Object>> confirmAction() {
        return args -> ToolActivity.observe("confirmAction", args, () -> {
            log.info("Tool: confirmAction invoked — action='{}', importance={}", args.action(), args.importance());
            return awaiting("confirmation", Map.of(
                    "action", nullSafe(args.action()),
                    "summary", nullSafe(args.summary()),
                    "details", nullSafe(args.details()),
                    "importance", args.importance() == null ? "medium" : args.importance()));
        });
    }

    @Bean
    @Description("Proposes a set of field values for the merchant to review and correct before they "
            + "are applied — a draft product, a price change, an edited description. Send the values "
            + "you would have used. Whatever the merchant sends back is what should actually be applied, "
            + "including any changes they made.")
    public Function<ProposeEditArgs, Map<String, Object>> proposeEdit() {
        return args -> ToolActivity.observe("proposeEdit", args, () -> {
            log.info("Tool: proposeEdit invoked — entityType={}, fields={}", args.entityType(),
                    args.fields() == null ? 0 : args.fields().size());
            return awaiting("edit", Map.of(
                    "entityType", nullSafe(args.entityType()),
                    "entityId", nullSafe(args.entityId()),
                    "summary", nullSafe(args.summary()),
                    "fields", args.fields() == null ? List.of() : args.fields()));
        });
    }

    /**
     * The shape every one of these returns. {@code awaitingUser} is the flag the client
     * keys on to know the run ended in a question rather than a completed action.
     */
    private static Map<String, Object> awaiting(String kind, Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "AWAITING_USER");
        result.put("kind", kind);
        result.put("awaitingUser", true);
        result.put("payload", payload);
        result.put("instruction", AWAIT_INSTRUCTION);
        return result;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
