package uz.uzex.agent.answering;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import uz.uzex.agent.answering.DraftParser.Draft;
import uz.uzex.agent.config.AgentProperties;
import uz.uzex.agent.dataquery.QueryResult;

/** Builds every response the agent can give, so their shape is decided in one place. */
@Component
public class AgentResponses {

    private static final int FALLBACK_MAX_ROWS = 10;

    private final String model;

    public AgentResponses(AgentProperties properties) {
        this.model = properties.model();
    }

    public AgentResponse verified(Draft draft, Lang lang, Trace trace, long durationMs) {
        return new AgentResponse(draft.status().name(), draft.answer(), draft.assumptions(), evidence(trace),
                true, "LLM", lang.name(), model, durationMs);
    }

    /** The draft cannot be trusted, so show the facts themselves or say there is no answer. */
    public AgentResponse fallback(Draft draft, Lang lang, Trace trace, long durationMs) {
        Trace.Step step = trace.lastStepWithRows();
        if (step == null || draft.status() == DraftParser.Status.NO_DATA) {
            return noData(lang, trace, durationMs);
        }
        return new AgentResponse("ANSWER", renderRows(lang, step.result()), List.of(), evidence(trace),
                true, "FALLBACK", lang.name(), model, durationMs);
    }

    public AgentResponse noData(Lang lang, Trace trace, long durationMs) {
        return new AgentResponse("NO_DATA", lang.noData, List.of(), evidence(trace),
                true, "SYSTEM", lang.name(), model, durationMs);
    }

    public AgentResponse error(String message, Lang lang, Trace trace, long durationMs) {
        return new AgentResponse("ERROR", message, List.of(), evidence(trace),
                false, "SYSTEM", lang.name(), model, durationMs);
    }

    public AgentResponse busy(String question) {
        Lang lang = Lang.detect(question);
        return new AgentResponse("ERROR", lang.busy, List.of(), List.of(), false, "SYSTEM", lang.name(), model, 0);
    }

    private static String renderRows(Lang lang, QueryResult result) {
        String rows = result.rows().stream()
                .limit(FALLBACK_MAX_ROWS)
                .map(row -> IntStream.range(0, row.size())
                        .filter(i -> row.get(i) != null)
                        .mapToObj(i -> result.columns().get(i) + ": " + row.get(i))
                        .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("\n"));
        return lang.fallbackPrefix + "\n" + rows;
    }

    private static List<AgentResponse.Evidence> evidence(Trace trace) {
        return trace.steps().stream()
                .map(s -> new AgentResponse.Evidence(s.kind(), s.input(), s.purpose(), s.result(), s.error()))
                .toList();
    }
}
