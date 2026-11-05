package uz.uzex.agent.answering;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;
import uz.uzex.agent.config.AgentProperties;
import uz.uzex.agent.dataquery.QueryRunner;
import uz.uzex.agent.dataquery.QueryResult;
import uz.uzex.agent.dataquery.SqlGuard;
import uz.uzex.agent.dataquery.SqlRejectedException;

/** Created per question, so the tools record into that question's trace. */
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FIND_COMPANY_SQL = """
            WITH q AS (SELECT spelled_key(?) AS spelled, loose_key(?) AS loose)
            SELECT p.inn, p.name, p.region, p.activity, p.role,
                   CASE WHEN p.spelled_key = q.spelled THEN 'exact'
                        WHEN p.loose_key = q.loose THEN 'same_letters'
                        WHEN p.loose_key LIKE '%' || q.loose || '%' THEN 'contains'
                        ELSE 'similar' END AS match,
                   (SELECT COUNT(*) FROM deals d WHERE d.seller_inn = p.inn OR d.buyer_inn = p.inn) AS deals
            FROM participants p CROSS JOIN q
            WHERE p.inn = ? OR p.loose_key = q.loose OR p.loose_key LIKE '%' || q.loose || '%'
               OR similarity(p.loose_key, q.loose) >= 0.45
            ORDER BY (p.inn = ?) DESC, (p.spelled_key = q.spelled) DESC, (p.loose_key = q.loose) DESC,
                     similarity(p.loose_key, q.loose) DESC, p.inn
            LIMIT 8
            """;
    private static final String FIND_BROKER_SQL = """
            WITH q AS (SELECT spelled_key(?) AS spelled, loose_key(?) AS loose)
            SELECT b.code, b.name, b.inn, b.region,
                   CASE WHEN b.spelled_key = q.spelled OR upper(b.code) = upper(?) THEN 'exact'
                        WHEN b.loose_key = q.loose THEN 'same_letters'
                        WHEN b.loose_key LIKE '%' || q.loose || '%' THEN 'contains'
                        ELSE 'similar' END AS match
            FROM brokers b CROSS JOIN q
            WHERE upper(b.code) = upper(?) OR b.inn = ? OR b.loose_key = q.loose
               OR b.loose_key LIKE '%' || q.loose || '%' OR similarity(b.loose_key, q.loose) >= 0.45
            ORDER BY (upper(b.code) = upper(?)) DESC, (b.spelled_key = q.spelled) DESC,
                     similarity(b.loose_key, q.loose) DESC, b.code
            LIMIT 8
            """;

    private final SqlGuard guard;
    private final QueryRunner executor;
    private final AgentProperties properties;
    private final Trace trace;
    private final ProgressListener progress;
    private final Lang lang;

    public AgentTools(SqlGuard guard, QueryRunner executor, AgentProperties properties,
            Trace trace, ProgressListener progress, Lang lang) {
        this.guard = guard;
        this.executor = executor;
        this.properties = properties;
        this.trace = trace;
        this.progress = progress;
        this.lang = lang;
    }

    @Tool(name = "find_company", description = "Find companies (sellers and buyers) by name in any spelling or script, or by 9-digit INN. Returns candidates with INN and match type.")
    public String findCompany(@ToolParam(description = "Company name or INN as written in the question") String query) {
        progress.step(lang.stepCompany + ": " + query);
        return lookup("company", query, FIND_COMPANY_SQL, query, query, query, query);
    }

    @Tool(name = "find_broker", description = "Find brokers by name, by code such as BK001, or by INN. Returns candidates with code and match type.")
    public String findBroker(@ToolParam(description = "Broker name, code or INN as written in the question") String query) {
        progress.step(lang.stepBroker + ": " + query);
        return lookup("broker", query, FIND_BROKER_SQL, query, query, query, query, query, query);
    }

    @Tool(name = "run_sql", description = "Run one read-only PostgreSQL SELECT against the deals database and return its rows.")
    public String runSql(
            @ToolParam(description = "One PostgreSQL SELECT statement") String sql,
            @ToolParam(description = "Short description of what this query finds, in the language of the question") String purpose) {
        progress.step(lang.stepSql + (purpose == null || purpose.isBlank() ? "" : ": " + purpose));
        if (trace.sqlCalls() >= properties.maxSqlCalls()) {
            return error("Query limit reached. Give your final JSON answer from the results you already have.");
        }
        try {
            String checked = guard.validate(sql);
            QueryResult result = executor.run(checked);
            trace.add(new Trace.Step("sql", checked, purpose, result, null));
            return resultJson(result);
        } catch (SqlRejectedException e) {
            trace.add(new Trace.Step("sql", sql, purpose, null, e.getMessage()));
            return error(e.getMessage());
        } catch (SQLException e) {
            log.warn("SQL failed: {}", e.getMessage());
            trace.add(new Trace.Step("sql", sql, purpose, null, e.getMessage()));
            return error(e.getMessage());
        }
    }

    private String lookup(String kind, String query, String sql, Object... params) {
        try {
            QueryResult result = executor.run(sql, params);
            trace.add(new Trace.Step(kind, query, null, result, null));
            return resultJson(result);
        } catch (SQLException e) {
            log.warn("Lookup failed: {}", e.getMessage());
            trace.add(new Trace.Step(kind, query, null, null, e.getMessage()));
            return error("Lookup failed.");
        }
    }

    private String resultJson(QueryResult result) {
        List<List<String>> shown = result.rows().stream().limit(properties.rowsForLlm()).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", result.columns());
        body.put("rows", shown);
        body.put("row_count", result.rows().size());
        if (result.truncated() || shown.size() < result.rows().size()) {
            body.put("note", "Rows were cut off. Do not count them, use COUNT or narrow the query.");
        }
        return JSON.writeValueAsString(body);
    }

    private static String error(String message) {
        return JSON.writeValueAsString(Map.of("error", message));
    }
}
