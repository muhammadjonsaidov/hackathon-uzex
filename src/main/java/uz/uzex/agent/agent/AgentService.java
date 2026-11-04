package uz.uzex.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import uz.uzex.agent.AgentProperties;
import uz.uzex.agent.agent.AnswerVerifier.Sources;
import uz.uzex.agent.agent.AnswerVerifier.Verdict;
import uz.uzex.agent.agent.DraftParser.Draft;
import uz.uzex.agent.agent.DraftParser.Status;
import uz.uzex.agent.sql.QueryExecutor;
import uz.uzex.agent.sql.QueryResult;
import uz.uzex.agent.sql.SqlGuard;

/**
 * One question: the LLM plans and words the answer, code supplies and checks every fact.
 * A draft that fails the check gets one repair attempt. After that the raw query result is
 * shown instead of unverified text, so a figure that is not in the data cannot reach the page.
 */
@Service
public class AgentService {

    public record Turn(String question, String answer) {}

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int FALLBACK_MAX_ROWS = 10;

    private final ChatClient chatClient;
    private final SystemPrompt systemPrompt;
    private final SqlGuard guard;
    private final QueryExecutor executor;
    private final AnswerVerifier verifier;
    private final AgentProperties properties;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public AgentService(ChatClient.Builder chatClientBuilder, SystemPrompt systemPrompt, SqlGuard guard,
            QueryExecutor executor, AnswerVerifier verifier, AgentProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.systemPrompt = systemPrompt;
        this.guard = guard;
        this.executor = executor;
        this.verifier = verifier;
        this.properties = properties;
    }

    public AgentResponse ask(String question, List<Turn> history, ProgressListener progress) {
        long start = System.nanoTime();
        Lang lang = Lang.detect(question);
        Trace trace = new Trace();
        if (!properties.llmConfigured()) {
            return system("ERROR", lang.llmError, lang, trace, start);
        }
        Future<AgentResponse> work = workers.submit(() -> answer(question, history, lang, trace, progress, start));
        try {
            return work.get(properties.deadlineSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            work.cancel(true);
            log.warn("Deadline passed for question: {}", question);
            return system("ERROR", lang.timeout, lang, trace, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return system("ERROR", lang.llmError, lang, trace, start);
        } catch (ExecutionException e) {
            log.error("Agent failed for question: {}", question, e.getCause());
            return system("ERROR", lang.llmError, lang, trace, start);
        }
    }

    private AgentResponse answer(String question, List<Turn> history, Lang lang, Trace trace,
            ProgressListener progress, long start) {
        progress.step(lang.stepThinking);
        String system = systemPrompt.forLanguage(lang);
        AgentTools tools = new AgentTools(guard, executor, properties, trace, progress, lang);
        List<Message> messages = conversation(system, question, history);

        String content = call(messages, tools);
        Draft draft = DraftParser.parse(content);

        progress.step(lang.stepVerify);
        Verdict verdict = check(draft, question, trace);
        String problem = problemWith(draft, verdict, trace);
        if (problem != null && secondsSince(start) < properties.repairBudgetSeconds()) {
            log.info("Repairing draft: {}", problem);
            messages.add(new AssistantMessage(content == null ? "" : content));
            messages.add(new UserMessage("Your answer was rejected by the automatic check: " + problem
                    + ". Every figure and name must come from a tool result. Call run_sql for anything missing, "
                    + "or remove it. Reply again with the final JSON only."));
            content = call(messages, tools);
            draft = DraftParser.parse(content);
            progress.step(lang.stepVerify);
            verdict = check(draft, question, trace);
            problem = problemWith(draft, verdict, trace);
        }

        if (problem != null) {
            log.warn("Draft still rejected, using fallback: {}", problem);
            return fallback(draft, lang, trace, start);
        }
        return new AgentResponse(draft.status().name(), draft.answer(), draft.assumptions(), evidence(trace),
                true, "LLM", lang.name(), properties.model(), elapsed(start));
    }

    private String call(List<Message> messages, AgentTools tools) {
        ChatResponse response = chatClient.prompt(new Prompt(List.copyOf(messages), options()))
                .tools(tools)
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private OpenAiChatOptions options() {
        var builder = OpenAiChatOptions.builder().model(properties.model()).temperature(0.0);
        if (properties.disableThinking()) {
            // DeepSeek thinks by default, and then wants reasoning_content replayed on every tool turn.
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private List<Message> conversation(String system, String question, List<Turn> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        List<Turn> recent = history == null ? List.of()
                : history.subList(Math.max(0, history.size() - properties.historyTurns()), history.size());
        for (Turn turn : recent) {
            messages.add(new UserMessage(limit(turn.question(), properties.maxQuestionLength())));
            messages.add(new AssistantMessage(limit(turn.answer(), 800)));
        }
        messages.add(new UserMessage(question));
        return messages;
    }

    private Verdict check(Draft draft, String question, Trace trace) {
        String text = draft.answer() + "\n" + String.join("\n", draft.assumptions());
        // "No answer" must stand alone. A figure beside it is a figure where the data has no answer,
        // even when a query returned it, so only the question and the coverage dates are allowed.
        List<String> evidence = draft.status() == Status.NO_DATA ? List.of() : trace.evidenceCells();
        return verifier.verify(text, new Sources(evidence, question, systemPrompt.referenceText()));
    }

    /** Null when the draft may be shown. */
    private String problemWith(Draft draft, Verdict verdict, Trace trace) {
        if (draft.answer().isBlank()) {
            return "the answer is empty";
        }
        if (!verdict.ok()) {
            return draft.status() == Status.NO_DATA
                    ? "a NO_DATA answer may only say what is missing and the data coverage, with no figures or company details: "
                            + verdict.describe()
                    : verdict.describe();
        }
        return switch (draft.status()) {
            case ANSWER -> trace.hasSqlRows() || trace.hasLookupRows() ? null : "status ANSWER needs a tool result with rows, and there is none";
            case CLARIFY -> trace.hasLookupRows() ? null : "status CLARIFY needs candidates from find_company or find_broker";
            case NO_DATA, GLOSSARY -> null;
        };
    }

    /** The draft cannot be trusted, so show the facts themselves or say there is no answer. */
    private AgentResponse fallback(Draft draft, Lang lang, Trace trace, long start) {
        Trace.Step step = trace.lastStepWithRows();
        if (step == null || draft.status() == Status.NO_DATA) {
            return system("NO_DATA", lang.noData, lang, trace, start);
        }
        return new AgentResponse("ANSWER", renderRows(lang, step.result()), List.of(), evidence(trace),
                true, "FALLBACK", lang.name(), properties.model(), elapsed(start));
    }

    private static String renderRows(Lang lang, QueryResult result) {
        String rows = result.rows().stream()
                .limit(FALLBACK_MAX_ROWS)
                .map(row -> IntStream.range(0, row.size())
                        .filter(i -> row.get(i) != null)
                        .mapToObj(i -> result.columns().get(i) + ": " + row.get(i))
                        .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("\n"));
        return lang.fallbackPrefix + "\n" + rows;
    }

    private AgentResponse system(String status, String message, Lang lang, Trace trace, long start) {
        return new AgentResponse(status, message, List.of(), evidence(trace),
                Objects.equals(status, "NO_DATA"), "SYSTEM", lang.name(), properties.model(), elapsed(start));
    }

    private static List<AgentResponse.Evidence> evidence(Trace trace) {
        return trace.steps().stream()
                .map(s -> new AgentResponse.Evidence(s.kind(), s.input(), s.purpose(), s.result(), s.error()))
                .toList();
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static long secondsSince(long start) {
        return (System.nanoTime() - start) / 1_000_000_000;
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
