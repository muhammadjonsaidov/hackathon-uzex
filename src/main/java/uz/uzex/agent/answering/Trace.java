package uz.uzex.agent.answering;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import uz.uzex.agent.dataquery.QueryResult;

/** Everything the tools did for one question: what the page shows and what the verifier checks. */
public final class Trace {

    /** kind is "sql", "company" or "broker"; input is the SQL text or the search term. */
    public record Step(String kind, String input, String purpose, QueryResult result, String error) {
        public boolean succeeded() {
            return error == null && result != null;
        }
    }

    private static final Pattern COUNT_COLUMN = Pattern.compile("(?i)(deals|count|cnt|annulled)");

    private final List<Step> steps = new CopyOnWriteArrayList<>();
    private final Consumer<Step> onStep;

    public Trace() {
        this(step -> {});
    }

    /** Announces each step as it happens, so the page can show evidence while the agent works. */
    public Trace(Consumer<Step> onStep) {
        this.onStep = onStep;
    }

    public void add(Step step) {
        steps.add(step);
        onStep.accept(step);
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public int sqlCalls() {
        return (int) steps.stream().filter(s -> s.kind().equals("sql")).count();
    }

    public boolean hasSqlRows() {
        return steps.stream().anyMatch(s -> s.kind().equals("sql") && s.succeeded() && !s.result().isEmpty());
    }

    public boolean hasLookupRows() {
        return steps.stream().anyMatch(s -> !s.kind().equals("sql") && s.succeeded() && !s.result().isEmpty());
    }

    /** The latest query result if there is one, otherwise the latest lookup result. */
    public Step lastStepWithRows() {
        Step lastSql = null;
        Step lastLookup = null;
        for (Step step : steps) {
            if (step.succeeded() && !step.result().isEmpty()) {
                if (step.kind().equals("sql")) {
                    lastSql = step;
                } else {
                    lastLookup = step;
                }
            }
        }
        return lastSql != null ? lastSql : lastLookup;
    }

    /** Names of the companies and brokers that lookups returned, in the "name" column. */
    public List<String> lookupNames() {
        List<String> names = new ArrayList<>();
        for (Step step : steps) {
            if (!step.kind().equals("sql") && step.succeeded()) {
                int column = step.result().columns().indexOf("name");
                if (column >= 0) {
                    step.result().rows().forEach(row -> names.add(row.get(column)));
                }
            }
        }
        return names;
    }

    /** Every cell returned by any tool, the only source of figures an answer may use. */
    public List<String> evidenceCells() {
        List<String> cells = new ArrayList<>();
        for (Step step : steps) {
            if (step.succeeded()) {
                step.result().rows().forEach(row -> row.stream().filter(Objects::nonNull).forEach(cells::add));
                if (step.kind().equals("sql")) {
                    cells.addAll(dealCountTotals(step.result()));
                }
            }
        }
        return cells;
    }

    /**
     * Totals of deal-count columns across complete result rows, such as deals per unit. Counts add
     * up whatever the unit. Volumes and amounts do not, so they must come from SQL.
     */
    static List<String> dealCountTotals(QueryResult result) {
        List<String> totals = new ArrayList<>();
        if (result.rows().size() < 2 || result.truncated()) {
            return totals;
        }
        for (int c = 0; c < result.columns().size(); c++) {
            if (!COUNT_COLUMN.matcher(result.columns().get(c)).find()) {
                continue;
            }
            long total = 0;
            boolean countable = true;
            for (List<String> row : result.rows()) {
                String value = row.get(c);
                if (value == null || !value.matches("\\d+")) {
                    countable = false;
                    break;
                }
                total += Long.parseLong(value);
            }
            if (countable) {
                totals.add(Long.toString(total));
            }
        }
        return totals;
    }
}
