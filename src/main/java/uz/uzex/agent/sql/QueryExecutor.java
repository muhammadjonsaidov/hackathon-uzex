package uz.uzex.agent.sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import uz.uzex.agent.AgentProperties;

@Service
public class QueryExecutor {

    private static final int MAX_SCALE = 6;

    private final DataSource dataSource;
    private final AgentProperties properties;

    public QueryExecutor(DataSource dataSource, AgentProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /** Runs already-validated SQL or a trusted parameterised lookup. */
    public QueryResult run(String sql, Object... params) throws SQLException {
        long start = System.nanoTime();
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(properties.sqlTimeoutSeconds());
            statement.setMaxRows(properties.maxRows() + 1);
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return read(resultSet, start);
            }
        }
    }

    private QueryResult read(ResultSet resultSet, long start) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() == properties.maxRows()) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>(columns.size());
            for (int i = 1; i <= columns.size(); i++) {
                row.add(format(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, truncated, (System.nanoTime() - start) / 1_000_000);
    }

    static String format(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal decimal -> formatDecimal(decimal);
            case Double number -> formatDecimal(BigDecimal.valueOf(number));
            case Float number -> formatDecimal(BigDecimal.valueOf(number));
            default -> value.toString();
        };
    }

    private static String formatDecimal(BigDecimal decimal) {
        BigDecimal bounded = decimal.scale() > MAX_SCALE ? decimal.setScale(MAX_SCALE, RoundingMode.HALF_UP) : decimal;
        return bounded.signum() == 0 ? "0" : bounded.stripTrailingZeros().toPlainString();
    }
}
