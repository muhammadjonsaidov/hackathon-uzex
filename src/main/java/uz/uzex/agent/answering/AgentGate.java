package uz.uzex.agent.answering;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.uzex.agent.config.AgentProperties;

/** Limits how many questions run at once, so a burst cannot exhaust the LLM or the database. */
@Service
public class AgentGate {

    private static final Logger log = LoggerFactory.getLogger(AgentGate.class);
    private static final int WAIT_SECONDS = 20;

    private final AgentService agent;
    private final AgentResponses responses;
    private final Semaphore slots;

    public AgentGate(AgentService agent, AgentResponses responses, AgentProperties properties) {
        this.agent = agent;
        this.responses = responses;
        this.slots = new Semaphore(properties.maxConcurrent());
    }

    public AgentResponse ask(String question, List<AgentService.Turn> history, Lang chatLanguage, ProgressListener progress) {
        try {
            if (!slots.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return responses.busy(question);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return responses.busy(question);
        }
        try {
            AgentResponse response = agent.ask(question, history, chatLanguage, progress);
            log.info("status={} source={} ms={} q={}", response.status(), response.source(), response.durationMs(), question);
            return response;
        } finally {
            slots.release();
        }
    }
}
