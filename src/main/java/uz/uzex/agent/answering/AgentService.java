package uz.uzex.agent.answering;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.uzex.agent.answering.DraftParser.Draft;
import uz.uzex.agent.answering.LlmGateway.Message;
import uz.uzex.agent.answering.LlmGateway.Role;
import uz.uzex.agent.config.AgentProperties;
import uz.uzex.agent.dataquery.QueryRunner;
import uz.uzex.agent.dataquery.SqlGuard;

/**
 * One question: the LLM plans and words the answer, code supplies and checks every fact.
 * A draft that fails the check gets one repair attempt. After that the raw query result is
 * shown instead of unverified text, so a figure that is not in the data cannot reach the page.
 */
@Service
public class AgentService {

    public record Turn(String question, String answer) {}

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_HISTORY_ANSWER_LENGTH = 800;

    private final LlmGateway llm;
    private final PromptSource prompt;
    private final SqlGuard guard;
    private final QueryRunner queries;
    private final DraftReviewer reviewer;
    private final AgentResponses responses;
    private final AgentProperties properties;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public AgentService(LlmGateway llm, PromptSource prompt, SqlGuard guard, QueryRunner queries,
            DraftReviewer reviewer, AgentResponses responses, AgentProperties properties) {
        this.llm = llm;
        this.prompt = prompt;
        this.guard = guard;
        this.queries = queries;
        this.reviewer = reviewer;
        this.responses = responses;
        this.properties = properties;
    }

    public AgentResponse ask(String question, List<Turn> history, Lang chatLanguage, ProgressListener progress) {
        long start = System.nanoTime();
        Lang lang = Lang.detect(question, chatLanguage);
        Trace trace = new Trace(step -> progress.evidence(AgentResponse.Evidence.of(step)));
        if (!properties.llmConfigured()) {
            return responses.error(lang.llmError, lang, trace, elapsedMs(start));
        }
        Future<AgentResponse> work = workers.submit(() -> answer(question, history, lang, trace, progress, start));
        try {
            return work.get(properties.deadlineSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            work.cancel(true);
            log.warn("Deadline passed for question: {}", question);
            return responses.error(lang.timeout, lang, trace, elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return responses.error(lang.llmError, lang, trace, elapsedMs(start));
        } catch (ExecutionException e) {
            log.error("Agent failed for question: {}", question, e.getCause());
            return responses.error(lang.llmError, lang, trace, elapsedMs(start));
        }
    }

    private AgentResponse answer(String question, List<Turn> history, Lang lang, Trace trace,
            ProgressListener progress, long start) {
        progress.step(lang.stepThinking);
        AgentTools tools = new AgentTools(guard, queries, properties, trace, progress, lang);
        List<Message> messages = conversation(prompt.forLanguage(lang), question, history);

        String content = llm.complete(List.copyOf(messages), tools);
        Draft draft = DraftParser.parse(content);
        progress.step(lang.stepVerify);
        Optional<String> problem = reviewer.review(draft, question, trace);
        if (problem.isEmpty() && !lang.matchesScript(draft.answer())) {
            // Worth one retry but not a fallback: correct figures in the wrong script beat a raw table.
            problem = Optional.of("the answer must be written in " + lang.promptName + ", keeping data values unchanged");
        }

        if (problem.isPresent() && secondsSince(start) < properties.repairBudgetSeconds()) {
            log.info("Repairing draft: {}", problem.get());
            messages.add(new Message(Role.ASSISTANT, content == null ? "" : content));
            messages.add(new Message(Role.USER, repairRequest(problem.get())));
            draft = DraftParser.parse(llm.complete(List.copyOf(messages), tools));
            progress.step(lang.stepVerify);
            problem = reviewer.review(draft, question, trace);
        }

        if (problem.isPresent()) {
            log.warn("Draft still rejected, using fallback: {}", problem.get());
            return responses.fallback(draft, lang, trace, elapsedMs(start));
        }
        return responses.verified(draft, lang, trace, elapsedMs(start));
    }

    private static String repairRequest(String problem) {
        return "Your answer was rejected by the automatic check: " + problem
                + ". Every figure and name must come from a tool result. Call run_sql for anything missing, "
                + "or remove it. Reply again with the final JSON only.";
    }

    private List<Message> conversation(String system, String question, List<Turn> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(Role.SYSTEM, system));
        int from = Math.max(0, history.size() - properties.historyTurns());
        for (Turn turn : history.subList(from, history.size())) {
            messages.add(new Message(Role.USER, limit(turn.question(), properties.maxQuestionLength())));
            messages.add(new Message(Role.ASSISTANT, limit(turn.answer(), MAX_HISTORY_ANSWER_LENGTH)));
        }
        messages.add(new Message(Role.USER, question));
        return messages;
    }

    private static long elapsedMs(long start) {
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
