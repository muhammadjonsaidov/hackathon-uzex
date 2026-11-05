package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uz.uzex.agent.AgentProperties;
import uz.uzex.agent.account.AccountService;
import uz.uzex.agent.account.ChatStore;
import uz.uzex.agent.agent.AgentResponse;
import uz.uzex.agent.agent.AgentService;
import uz.uzex.agent.agent.Lang;
import uz.uzex.agent.agent.ProgressListener;
import uz.uzex.agent.sql.QueryExecutor;

@RestController
public class AskController {

    public record AskRequest(String question, List<AgentService.Turn> history, UUID chatId, String lang) {}

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long SSE_TIMEOUT_MS = 100_000;
    private static final long WINDOW_MS = 60_000;

    private final AgentService agent;
    private final AgentProperties properties;
    private final QueryExecutor executor;
    private final AccountService accounts;
    private final ChatStore chats;
    private final Semaphore slots;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public AskController(AgentService agent, AgentProperties properties, QueryExecutor executor,
            AccountService accounts, ChatStore chats) {
        this.agent = agent;
        this.properties = properties;
        this.executor = executor;
        this.accounts = accounts;
        this.chats = chats;
        this.slots = new Semaphore(properties.maxConcurrent());
    }

    /** Stateless: nothing is stored, and context comes from the request. */
    @PostMapping("/api/ask")
    public ResponseEntity<AgentResponse> ask(@RequestBody AskRequest request, HttpServletRequest http) {
        String question = clean(request.question());
        if (question == null) {
            return ResponseEntity.badRequest().build();
        }
        if (overLimit(http)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(busy(question));
        }
        return ResponseEntity.ok(guarded(question, request.history(), null, ProgressListener.NONE));
    }

    /** What the page uses. Context comes from the stored chat, never from the browser. */
    @PostMapping(value = "/api/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AskRequest request, HttpServletRequest http, HttpServletResponse response) {
        String question = clean(request.question());
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty question");
        }
        // Answering matters more than saving: if storage fails, the question is still answered.
        Optional<UUID> user = currentUser(http, response);
        Optional<ChatStore.ChatSummary> existing = request.chatId() == null || user.isEmpty()
                ? Optional.empty()
                : Optional.of(chats.find(user.get(), request.chatId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        boolean limited = overLimit(http);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        workers.submit(() -> {
            try {
                if (limited) {
                    send(emitter, "final", busy(question));
                    emitter.complete();
                    return;
                }
                String lang = existing.map(ChatStore.ChatSummary::lang).orElseGet(() -> chatLanguage(request.lang(), question));
                Optional<ChatStore.ChatSummary> chat = existing.isPresent() ? existing : startChat(user, lang, question);
                chat.ifPresent(c -> send(emitter, "chat", c));
                List<AgentService.Turn> history = chat.map(c -> history(c.id())).orElse(List.of());
                AgentResponse answer = guarded(question, history, "ru".equals(lang) ? Lang.RU : Lang.UZ_LATN,
                        label -> send(emitter, "step", Map.of("label", label)));
                chat.ifPresent(c -> save(c.id(), question, answer));
                send(emitter, "final", answer);
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

    private Optional<UUID> currentUser(HttpServletRequest http, HttpServletResponse response) {
        try {
            return Optional.of(accounts.require(http, response));
        } catch (RuntimeException e) {
            log.error("Could not resolve the user, answering without saving", e);
            return Optional.empty();
        }
    }

    private Optional<ChatStore.ChatSummary> startChat(Optional<UUID> user, String lang, String question) {
        try {
            return user.map(u -> chats.create(u, lang, question));
        } catch (RuntimeException e) {
            log.error("Could not create a chat, answering without saving", e);
            return Optional.empty();
        }
    }

    private List<AgentService.Turn> history(UUID chat) {
        try {
            return chats.history(chat, properties.historyTurns());
        } catch (RuntimeException e) {
            log.error("Could not read chat history, answering without it", e);
            return List.of();
        }
    }

    private void save(UUID chat, String question, AgentResponse answer) {
        try {
            chats.addTurn(chat, question, answer);
        } catch (RuntimeException e) {
            log.error("Could not save the answer", e);
        }
    }

    private static String chatLanguage(String requested, String question) {
        if ("ru".equals(requested) || "uz".equals(requested)) {
            return requested;
        }
        return Lang.detect(question) == Lang.RU ? "ru" : "uz";
    }

    private AgentResponse guarded(String question, List<AgentService.Turn> history, Lang chatLanguage, ProgressListener progress) {
        try {
            if (!slots.tryAcquire(20, TimeUnit.SECONDS)) {
                return busy(question);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return busy(question);
        }
        try {
            AgentResponse response = agent.ask(question, history, chatLanguage, progress);
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
