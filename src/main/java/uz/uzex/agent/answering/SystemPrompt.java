package uz.uzex.agent.answering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import uz.uzex.agent.config.AgentProperties;
import uz.uzex.agent.dataquery.QueryRunner;

/**
 * Builds the prompt from the database, so the catalog the LLM sees always matches the data.
 * Built once, on first use, because the static prefix is what DeepSeek's prompt cache rewards.
 */
@Component
public class SystemPrompt implements PromptSource {

    private static final String LANG_RULE = "{{LANG_RULE}}";
    private static final String CATALOG_SQL = """
            SELECT c.product, cat.product_uz, cat.aliases, cat.hs_code,
                   string_agg(DISTINCT c.unit, ', ') AS units,
                   string_agg(DISTINCT c.currency, ', ') AS currencies,
                   NULLIF(concat_ws('; ',
                     'grade: ' || string_agg(DISTINCT c.grade, ', '),
                     'class: ' || string_agg(DISTINCT c.class, ', '),
                     'brand: ' || string_agg(DISTINCT c.brand, ', '),
                     'size: ' || string_agg(DISTINCT c.size, ', '),
                     'ptype: ' || string_agg(DISTINCT c.ptype, ', ')), '') AS attributes
            FROM contracts c JOIN product_catalog cat ON cat.product = c.product
            GROUP BY 1, 2, 3, 4 ORDER BY 1
            """;

    private final QueryRunner executor;
    private final AgentProperties properties;
    private volatile String template;
    private volatile List<String> productTerms;

    public SystemPrompt(QueryRunner executor, AgentProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    @Override
    public String forLanguage(Lang lang) {
        return template().replace(LANG_RULE, lang.promptName + ", the language of the question");
    }

    /** Product names, aliases aside, and brands: what a "no answer" reply must not name. */
    @Override
    public List<String> productTerms() {
        List<String> current = productTerms;
        if (current == null) {
            try {
                current = executor.run("SELECT product FROM product_catalog "
                        + "UNION SELECT brand FROM contracts WHERE brand IS NOT NULL").rows().stream()
                        .map(List::getFirst)
                        .toList();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read product names", e);
            }
            productTerms = current;
        }
        return current;
    }

    /** Text the verifier accepts names and constants from, the same for every language. */
    @Override
    public String referenceText() {
        return template();
    }

    private String template() {
        String current = template;
        if (current == null) {
            synchronized (this) {
                current = template;
                if (current == null) {
                    current = build();
                    template = current;
                }
            }
        }
        return current;
    }

    private String build() {
        try {
            String text = new ClassPathResource("prompts/system.md").getContentAsString(StandardCharsets.UTF_8);
            return text
                    .replace("{{MAX_ROWS}}", String.valueOf(properties.maxRows()))
                    .replace("{{ROWS_FOR_LLM}}", String.valueOf(properties.rowsForLlm()))
                    .replace("{{STATUSES}}", distinct("deals", "status"))
                    .replace("{{CATEGORIES}}", distinct("contracts", "category"))
                    .replace("{{GROUPS}}", distinct("contracts", "product_group"))
                    .replace("{{INDUSTRIES}}", distinct("contracts", "industry"))
                    .replace("{{ACTIVITIES}}", distinct("participants", "activity"))
                    .replace("{{REGIONS}}", lines("SELECT region, region_ru, region_uz_cyr FROM regions ORDER BY region"))
                    .replace("{{CATALOG}}", lines(CATALOG_SQL));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not build the system prompt", e);
        }
    }

    private String distinct(String table, String column) throws SQLException {
        return executor.run("SELECT DISTINCT " + column + " FROM " + table + " ORDER BY 1").rows().stream()
                .map(List::getFirst)
                .collect(Collectors.joining(", "));
    }

    private String lines(String sql) throws SQLException {
        return executor.run(sql).rows().stream()
                .map(row -> row.stream().map(cell -> Objects.toString(cell, "")).collect(Collectors.joining(" | ")))
                .collect(Collectors.joining("\n"));
    }
}
