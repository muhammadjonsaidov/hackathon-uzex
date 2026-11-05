package uz.uzex.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM deals",
            "SELECT year, COUNT(*) FILTER (WHERE NOT is_annulled) AS valid_deals FROM deals GROUP BY year ORDER BY year",
            "SELECT round(SUM(amount_uzs), 2) FROM deals WHERE product = 'Мука' AND deal_date BETWEEN DATE '2025-01-01' AND DATE '2025-12-31'",
            "WITH t AS (SELECT buyer_inn, SUM(amount_uzs) s FROM deals WHERE NOT is_annulled GROUP BY 1) SELECT p.name, t.s FROM t JOIN participants p ON p.inn = t.buyer_inn ORDER BY t.s DESC LIMIT 5",
            "SELECT product, round(100.0 * SUM(amount_uzs) / SUM(SUM(amount_uzs)) OVER (), 2) AS share FROM deals GROUP BY product",
            "SELECT DISTINCT ON (product) product, deal_no FROM deals ORDER BY product, amount_uzs DESC",
            "SELECT date_trunc('month', deal_date)::date AS m, COUNT(*) FROM deals GROUP BY 1",
            "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY days_to_pay) FROM deals",
            "SELECT broker_name, COUNT(*) FROM broker_deals WHERE broker_side IN ('seller', 'both') GROUP BY 1",
            "SELECT * FROM public.deals WHERE seller_name ILIKE '%to‘her%'",
            "SELECT year FROM deals UNION ALL SELECT year FROM deals;",
            "SELECT name FROM participants WHERE name = 'update delete drop'"
    })
    void acceptsReadOnlyQueries(String sql) {
        assertThat(guard.validate(sql)).doesNotEndWith(";");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM deals",
            "DROP TABLE deals",
            "UPDATE deals SET amount = 0",
            "INSERT INTO deals (deal_no) VALUES (1)",
            "SELECT 1; DROP TABLE deals",
            "SELECT * FROM deals; SELECT * FROM brokers",
            "WITH x AS (DELETE FROM deals RETURNING *) SELECT * FROM x",
            "SELECT * FROM raw.deals",
            "SELECT * FROM pg_catalog.pg_user",
            "SELECT * FROM information_schema.tables",
            "SELECT pg_sleep(100)",
            "SELECT current_setting('server_version')",
            "SELECT * INTO copy_of_deals FROM deals",
            "COPY deals TO '/tmp/x'",
            "SELECT * FROM secrets",
            "SELECT product_uz FROM product_catalog",
            "SELECT region_uz_cyr FROM regions",
            "  ",
            "not sql at all"
    })
    void rejectsEverythingElse(String sql) {
        assertThatThrownBy(() -> guard.validate(sql)).isInstanceOf(SqlRejectedException.class);
    }
}
