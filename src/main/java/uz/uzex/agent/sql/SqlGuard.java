package uz.uzex.agent.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * First gate for SQL written by the LLM: one SELECT over the published tables only.
 * The database role is the second gate, since it is read-only and sees nothing else.
 */
@Component
public class SqlGuard {

    public static final Set<String> ALLOWED_TABLES = Set.of(
            "deals", "broker_deals", "contracts", "participants", "brokers",
            "usd_rates", "regions", "product_catalog");

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|into|update|delete|drop|alter|create|truncate|grant|revoke|merge|call|vacuum|copy"
                    + "|pg_\\w+|current_setting|set_config|dblink\\w*|lo_\\w+|nextval|setval"
                    + "|\\w+_to_xml\\w*|information_schema)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Returns the statement to execute, or throws with a message the LLM can act on. */
    public String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlRejectedException("Empty SQL.");
        }
        String trimmed = sql.strip();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        var withoutStrings = STRING_LITERAL.matcher(trimmed).replaceAll("''");
        var forbidden = FORBIDDEN.matcher(withoutStrings);
        if (forbidden.find()) {
            throw new SqlRejectedException("Forbidden keyword: " + forbidden.group(1) + ". Only read-only SELECT is allowed.");
        }

        Statement statement = parseSingle(trimmed);
        if (!(statement instanceof Select select)) {
            throw new SqlRejectedException("Only a single SELECT statement is allowed.");
        }
        Set<String> tables = new TablesNamesFinder<Void>().getTables((Statement) select).stream()
                .map(SqlGuard::bareName)
                .collect(Collectors.toSet());
        Set<String> unknown = tables.stream()
                .filter(t -> !ALLOWED_TABLES.contains(t))
                .collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new SqlRejectedException("Unknown tables " + unknown + ". Allowed: " + ALLOWED_TABLES);
        }
        return trimmed;
    }

    private static Statement parseSingle(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.size() != 1) {
                throw new SqlRejectedException("Exactly one statement is allowed.");
            }
            return statements.getFirst();
        } catch (JSQLParserException e) {
            throw new SqlRejectedException("Could not parse SQL, simplify the query: " + firstLine(e.getMessage()));
        }
    }

    private static String bareName(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace("\"", "");
        return lower.startsWith("public.") ? lower.substring("public.".length()) : lower;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
