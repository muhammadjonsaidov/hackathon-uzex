package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uz.uzex.agent.answering.AgentGate;
import uz.uzex.agent.answering.AgentResponse;
import uz.uzex.agent.answering.AgentResponses;
import uz.uzex.agent.answering.ProgressListener;
import uz.uzex.agent.chat.ChatAnswerService;
import uz.uzex.agent.chat.ChatRepository;
import uz.uzex.agent.chat.ChatSummary;
import uz.uzex.agent.config.AgentProperties;

@RestController
public class AskController {

    public record AskRequest(String question, UUID chatId, String lang) {}

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long SSE_TIMEOUT_MS = 100_000;

    private final AgentGate agent;
    private final AgentResponses responses;
    private final ChatAnswerService chatAnswers;
    private final ChatRepository chats;
    private final CurrentUser currentUser;
    private final int maxQuestionLength;
    private final RateLimiter limiter;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public AskController(AgentGate agent, AgentResponses responses, ChatAnswerService chatAnswers,
            ChatRepository chats, CurrentUser currentUser, AgentProperties properties) {
        this.agent = agent;
        this.responses = responses;
        this.chatAnswers = chatAnswers;
        this.chats = chats;
        this.currentUser = currentUser;
        this.maxQuestionLength = properties.maxQuestionLength();
        this.limiter = new RateLimiter(properties.requestsPerMinute(), Duration.ofMinutes(1));
    }

    /** Stateless: nothing is stored and there is no earlier context. */
    @PostMapping("/api/ask")
    public ResponseEntity<AgentResponse> ask(@RequestBody AskRequest request, HttpServletRequest http) {
        String question = clean(request.question());
        if (question == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!limiter.allow(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(responses.busy(question));
        }
        return ResponseEntity.ok(agent.ask(question, List.of(), null, ProgressListener.NONE));
    }

    /** What the page uses. Context comes from the stored chat, never from the browser. */
    @PostMapping(value = "/api/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AskRequest request, HttpServletRequest http, HttpServletResponse response) {
        String question = clean(request.question());
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty question");
        }
        Optional<UUID> user = userOrNone(http, response);
        Optional<ChatSummary> existing = ownedChat(user, request.chatId());
        boolean allowed = limiter.allow(http.getRemoteAddr());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        workers.submit(() -> {
            try {
                if (allowed) {
                    chatAnswers.answer(question, user, existing, request.lang(), new SseEvents(emitter));
                } else {
                    send(emitter, "final", responses.busy(question));
                }
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("Stream failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private Optional<UUID> userOrNone(HttpServletRequest http, HttpServletResponse response) {
        try {
            return Optional.of(currentUser.require(http, response));
        } catch (RuntimeException e) {
            log.error("Could not resolve the user, answering without saving", e);
            return Optional.empty();
        }
    }

    /** Someone else's chat answers exactly like a chat that does not exist. */
    private Optional<ChatSummary> ownedChat(Optional<UUID> user, UUID chatId) {
        if (chatId == null || user.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(chats.find(user.get(), chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private String clean(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String trimmed = question.strip();
        return trimmed.length() <= maxQuestionLength ? trimmed : trimmed.substring(0, maxQuestionLength);
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Client went away: {}", e.getMessage());
        }
    }

    private record SseEvents(SseEmitter emitter) implements ChatAnswerService.Events {
        @Override
        public void chat(ChatSummary chat) {
            send(emitter, "chat", chat);
        }

        @Override
        public void step(String label) {
            send(emitter, "step", Map.of("label", label));
        }

        @Override
        public void evidence(AgentResponse.Evidence evidence) {
            send(emitter, "evidence", evidence);
        }

        @Override
        public void answer(AgentResponse response) {
            send(emitter, "final", response);
        }
    }
}
