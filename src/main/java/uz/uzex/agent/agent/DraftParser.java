package uz.uzex.agent.agent;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the LLM's final message, tolerating code fences and text around the JSON. */
final class DraftParser {

    enum Status { ANSWER, NO_DATA, CLARIFY, GLOSSARY }

    record Draft(Status status, String answer, List<String> assumptions, boolean wellFormed) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DraftParser() {}

    static Draft parse(String content) {
        String text = content == null ? "" : content.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = JSON.readTree(text.substring(start, end + 1));
                String answer = node.path("answer").asString("");
                if (!answer.isBlank()) {
                    return new Draft(status(node.path("status").asString("")), answer.strip(), assumptions(node), true);
                }
            } catch (JacksonException e) {
                // Falls through to treating the message as plain text.
            }
        }
        return new Draft(Status.ANSWER, text, List.of(), false);
    }

    private static Status status(String raw) {
        try {
            return Status.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Status.ANSWER;
        }
    }

    private static List<String> assumptions(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node.path("assumptions")) {
            String value = item.asString("");
            if (!value.isBlank()) {
                values.add(value.strip());
            }
        }
        return values;
    }
}
