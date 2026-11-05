package uz.uzex.agent.web;

import java.sql.SQLException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.uzex.agent.config.AgentProperties;
import uz.uzex.agent.dataquery.QueryRunner;

@RestController
public class StatusController {

    private final QueryRunner queries;
    private final AgentProperties properties;

    public StatusController(QueryRunner queries, AgentProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of("database", databaseReady(), "llmConfigured", properties.llmConfigured());
    }

    private boolean databaseReady() {
        try {
            return !queries.run("SELECT 1 FROM deals LIMIT 1").rows().isEmpty();
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }
}
