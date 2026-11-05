package uz.uzex.agent.dataquery;

import java.util.List;
import java.util.Objects;

/** Rows are strings so what the verifier checks is exactly what the LLM and the page see. */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated, long durationMs) {

    /** No rows, or only an aggregate over nothing, such as a single row of NULL totals. */
    public boolean isEmpty() {
        return rows.isEmpty() || rows.stream().allMatch(row -> row.stream().allMatch(Objects::isNull));
    }
}
