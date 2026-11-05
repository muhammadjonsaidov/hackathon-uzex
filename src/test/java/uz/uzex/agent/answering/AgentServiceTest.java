package uz.uzex.agent.answering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import uz.uzex.agent.answering.LlmGateway.Message;
import uz.uzex.agent.config.AgentProperties;
import uz.uzex.agent.dataquery.QueryResult;
import uz.uzex.agent.dataquery.QueryRunner;
import uz.uzex.agent.dataquery.SqlGuard;

/** The whole answering flow with a scripted model in place of the real one. */
class AgentServiceTest {

    private static final String SQL = "SELECT COUNT(*) AS deals FROM deals";

    private final QueryRunner queries = (sql, params) ->
            new QueryResult(List.of("deals"), List.of(List.of("115763")), false, 1);

    private final PromptSource prompt = new PromptSource() {
        @Override
        public String forLanguage(Lang lang) {
            return "system prompt";
        }

        @Override
        public String referenceText() {
            return "Мука, Рис";
        }

        @Override
        public List<String> productTerms() {
            return List.of("Мука", "АИ-92");
        }
    };

    private final List<List<Message>> calls = new ArrayList<>();

    private static AgentProperties properties(String apiKey) {
        return new AgentProperties("test-model", apiKey, true, 10, 5, 8, 200, 50, 15, 8, 600, 30, 3);
    }

    /** Each entry is one model reply. It may call tools first, and returns the final text. */
    @SafeVarargs
    private AgentResponse ask(String question, List<AgentService.Turn> history, Function<AgentTools, String>... replies) {
        Deque<Function<AgentTools, String>> script = new ArrayDeque<>(List.of(replies));
        LlmGateway llm = (messages, tools) -> {
            calls.add(messages);
            return script.removeFirst().apply(tools);
        };
        AgentProperties properties = properties("key");
        AgentService service = new AgentService(llm, prompt, new SqlGuard(), queries,
                new DraftReviewer(new AnswerVerifier(), prompt), new AgentResponses(properties), properties);
        return service.ask(question, history, null, ProgressListener.NONE);
    }

    private static Function<AgentTools, String> withSql(String answer) {
        return tools -> {
            tools.runSql(SQL, "count");
            return json("ANSWER", answer);
        };
    }

    private static Function<AgentTools, String> reply(String status, String answer) {
        return tools -> json(status, answer);
    }

    private static String json(String status, String answer) {
        return "{\"status\":\"" + status + "\",\"answer\":\"" + answer + "\",\"assumptions\":[]}";
    }

    @Test
    void showsADraftThatMatchesTheData() {
        AgentResponse response = ask("Сколько сделок?", List.of(), withSql("Всего 115 763 сделки."));
        assertThat(response.status()).isEqualTo("ANSWER");
        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.verified()).isTrue();
        assertThat(response.evidence()).hasSize(1);
        assertThat(calls).hasSize(1);
    }

    @Test
    void sendsAnInventedFigureBackOnce() {
        AgentResponse response = ask("Сколько сделок?", List.of(),
                withSql("Всего 999 999 сделок."),
                reply("ANSWER", "Всего 115 763 сделки."));
        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).contains("115 763");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).getLast().text()).contains("rejected").contains("999 999");
    }

    @Test
    void showsTheRawResultWhenTheModelInventsTwice() {
        AgentResponse response = ask("Сколько сделок?", List.of(),
                withSql("Всего 999 999 сделок."),
                reply("ANSWER", "Всего 888 888 сделок."));
        assertThat(response.source()).isEqualTo("FALLBACK");
        assertThat(response.answer()).contains("deals: 115763").doesNotContain("999").doesNotContain("888");
    }

    @Test
    void refusesAnAnswerWithNoDataBehindIt() {
        AgentResponse response = ask("Кто покупатель?", List.of(),
                reply("ANSWER", "Покупатель найден."),
                reply("ANSWER", "Покупатель найден."));
        assertThat(response.status()).isEqualTo("NO_DATA");
        assertThat(response.source()).isEqualTo("SYSTEM");
    }

    @Test
    void aNoAnswerReplyMayNotAddFigures() {
        AgentResponse response = ask("Почему выросли цены?", List.of(),
                tools -> {
                    tools.runSql(SQL, "count");
                    return json("NO_DATA", "Причин нет, но сделок было 115 763.");
                },
                reply("NO_DATA", "В данных нет причин."));
        assertThat(response.answer()).isEqualTo("В данных нет причин.");
        assertThat(calls).hasSize(2);
    }

    @Test
    void aNoAnswerReplyMayNotNameOtherProducts() {
        AgentResponse response = ask("Какая цена АИ-98?", List.of(),
                reply("NO_DATA", "АИ-98 нет, есть только АИ-92."),
                reply("NO_DATA", "Марки АИ-98 в данных нет."));
        assertThat(response.answer()).isEqualTo("Марки АИ-98 в данных нет.");
    }

    @Test
    void aWrongScriptGetsOneRetryButNeverAFallback() {
        AgentResponse response = ask("2025 йилда нечта битим бўлган?", List.of(),
                withSql("2025-yilda jami 115 763 ta bitim bor edi."),
                reply("ANSWER", "2025-yilda jami 115 763 ta bitim bor edi."));
        assertThat(response.source()).isEqualTo("LLM");
        assertThat(calls).hasSize(2);
    }

    @Test
    void definitionsNeedNoQuery() {
        AgentResponse response = ask("Что значит статус?", List.of(), reply("GLOSSARY", "Это состояние сделки."));
        assertThat(response.status()).isEqualTo("GLOSSARY");
    }

    @Test
    void aModelFailureBecomesAClearError() {
        AgentResponse response = ask("Сколько сделок?", List.of(), tools -> {
            throw new IllegalStateException("provider down");
        });
        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.answer()).isEqualTo(Lang.RU.llmError);
        assertThat(response.verified()).isFalse();
    }

    @Test
    void forbiddenSqlIsRefusedAndNeverRun() {
        List<String> ran = new ArrayList<>();
        QueryRunner recording = (sql, params) -> {
            ran.add(sql);
            return new QueryResult(List.of(), List.of(), false, 0);
        };
        Trace trace = new Trace();
        AgentTools tools = new AgentTools(new SqlGuard(), recording, properties("key"), trace, ProgressListener.NONE, Lang.RU);
        assertThat(tools.runSql("SELECT * FROM app.users", "steal")).contains("error");
        assertThat(tools.runSql("DROP TABLE deals", "drop")).contains("error");
        assertThat(ran).isEmpty();
    }

    @Test
    void evidenceIsAnnouncedAsSoonAsAQueryFinishes() {
        List<String> order = new ArrayList<>();
        ProgressListener progress = new ProgressListener() {
            @Override
            public void step(String label) {}

            @Override
            public void evidence(AgentResponse.Evidence evidence) {
                order.add("evidence:" + evidence.result().rows().getFirst().getFirst());
            }
        };
        LlmGateway llm = (messages, tools) -> {
            tools.runSql(SQL, "count");
            order.add("model replied");
            return json("ANSWER", "Всего 115 763 сделки.");
        };
        AgentProperties properties = properties("key");
        AgentService service = new AgentService(llm, prompt, new SqlGuard(), queries,
                new DraftReviewer(new AnswerVerifier(), prompt), new AgentResponses(properties), properties);
        service.ask("Сколько сделок?", List.of(), null, progress);
        assertThat(order).containsExactly("evidence:115763", "model replied");
    }

    @Test
    void onlyTheLatestHistoryReachesTheModel() {
        List<AgentService.Turn> history = List.of(
                new AgentService.Turn("q1", "a1"), new AgentService.Turn("q2", "a2"),
                new AgentService.Turn("q3", "a3"), new AgentService.Turn("q4", "a4"));
        ask("Сколько сделок?", history, withSql("Всего 115 763 сделки."));
        List<String> texts = calls.getFirst().stream().map(Message::text).toList();
        assertThat(texts).doesNotContain("q1").contains("q2", "a2", "q4", "a4");
    }

    @Test
    void withoutAKeyItSaysSoWithoutCallingTheModel() {
        AgentProperties noKey = properties("replace-me");
        LlmGateway llm = (messages, tools) -> {
            throw new AssertionError("must not be called");
        };
        AgentService service = new AgentService(llm, prompt, new SqlGuard(), queries,
                new DraftReviewer(new AnswerVerifier(), prompt), new AgentResponses(noKey), noKey);
        assertThat(service.ask("Сколько?", List.of(), null, ProgressListener.NONE).status()).isEqualTo("ERROR");
    }
}
