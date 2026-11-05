package uz.uzex.agent.dataquery;

import java.sql.SQLException;

/** Runs read-only SQL against the deals data. */
@FunctionalInterface
public interface QueryRunner {

    QueryResult run(String sql, Object... params) throws SQLException;
}
