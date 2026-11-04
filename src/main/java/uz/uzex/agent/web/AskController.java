package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uz.uzex.agent.AgentProperties;
import uz.uzex.agent.agent.AgentResponse;
import uz.uzex.agent.agent.AgentService;
import uz.uzex.agent.agent.Lang;
import uz.uzex.agent.agent.ProgressListener;
import uz.uzex.agent.sql.QueryExecutor;

@RestController
public class AskController {

    public record AskRequest(String question, List<AgentService.Turn> history) {}

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long SSE_TIMEOUT_MS = 100_000;
    private static final long WINDOW_MS = 60_000;

    private final AgentService agent;
    private final AgentProperties properties;
    private final QueryExecutor executor;
    private final Semaphore slots;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public AskController(AgentService agent, AgentProperties properties, QueryExecutor executor) {
        this.agent = agent;
        this.properties = properties;
        this.executor = executor;
        this.slots = new Semaphore(properties.maxConcurrent());
    }

    @PostMapping("/api/ask")
    public ResponseEntity<AgentResponse> ask(@RequestBody AskRequest request, HttpServletRequest http) {
        String question = clean(request.question());
        if (question == null) {
            return ResponseEntity.badRequest().build();
        }
        if (overLimit(http)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(busy(question));
        }
        return ResponseEntity.ok(guarded(question, request.history(), ProgressListener.NONE));
    }

    @PostMapping(value = "/api/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AskRequest request, HttpServletRequest http) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String question = clean(request.question());
        boolean limited = question != null && overLimit(http);
        workers.submit(() -> {
            try {
                if (question == null) {
                    emitter.completeWithError(new IllegalArgumentException("Empty question"));
                    return;
                }
                AgentResponse response = limited ? busy(question)
                        : guarded(question, request.history(), label -> send(emitter, "step", Map.of("label", label)));
                send(emitter, "final", response);
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("Stream failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        boolean database;
        try {
            database = !executor.run("SELECT 1 FROM deals LIMIT 1").rows().isEmpty();
        } catch (SQLException | RuntimeException e) {
            database = false;
        }
        return Map.of("database", database, "llmConfigured", properties.llmConfigured(), "model", properties.model());
    }

    private AgentResponse guarded(String question, List<AgentService.Turn> history, ProgressListener progress) {
        try {
            if (!slots.tryAcquire(20, TimeUnit.SECONDS)) {
                return busy(question);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return busy(question);
        }
        try {
            AgentResponse response = agent.ask(question, history, progress);
            log.info("status={} source={} ms={} q={}", response.status(), response.source(), response.durationMs(), question);
            return response;
        } finally {
            slots.release();
        }
    }

    private AgentResponse busy(String question) {
        Lang lang = Lang.detect(question);
        return new AgentResponse("ERROR", lang.busy, List.of(), List.of(), false, "SYSTEM", lang.name(), properties.model(), 0);
    }

    private String clean(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String trimmed = question.strip();
        return trimmed.length() <= properties.maxQuestionLength() ? trimmed : trimmed.substring(0, properties.maxQuestionLength());
    }

    /** Protects the API key once the page is public. getRemoteAddr honours forwarded headers. */
    private boolean overLimit(HttpServletRequest http) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(http.getRemoteAddr(), (ip, current) ->
                current == null || now - current.startedAt > WINDOW_MS ? new Window(now) : current);
        return window.count.incrementAndGet() > properties.requestsPerMinute();
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Client went away: {}", e.getMessage());
        }
    }

    private static final class Window {
        final long startedAt;
        final AtomicInteger count = new AtomicInteger();

        Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
