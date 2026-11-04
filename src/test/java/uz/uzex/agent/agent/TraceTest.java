package uz.uzex.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uz.uzex.agent.sql.QueryResult;

class TraceTest {

    private static QueryResult result(boolean truncated) {
        return new QueryResult(
                List.of("unit", "all_deals", "annulled", "valid_volume"),
                List.of(List.of("литр", "3276", "214", "118644000"), List.of("тонна", "2023", "115", "54263")),
                truncated, 1);
    }

    @Test
    void totalsDealCountsAcrossRows() {
        assertThat(Trace.dealCountTotals(result(false))).containsExactly("5299", "329");
    }

    @Test
    void neverTotalsVolumesAcrossUnits() {
        assertThat(Trace.dealCountTotals(result(false))).doesNotContain("118698263");
    }

    @Test
    void doesNotTotalAnIncompleteResult() {
        assertThat(Trace.dealCountTotals(result(true))).isEmpty();
    }
}
