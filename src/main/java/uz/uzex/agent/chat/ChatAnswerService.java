package uz.uzex.agent.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.uzex.agent.answering.AgentGate;
import uz.uzex.agent.answering.AgentResponse;
import uz.uzex.agent.answering.AgentService;
import uz.uzex.agent.answering.Lang;
import uz.uzex.agent.answering.ProgressListener;
import uz.uzex.agent.config.AgentProperties;

/**
 * Answers a question inside a chat: context comes from the stored chat, and the answer is saved.
 * Answering matters more than saving, so a storage failure never stops the answer.
 */
@Service
public class ChatAnswerService {

    /** Where the steps of an answer go as they happen. */
    public interface Events {
        void chat(ChatSummary chat);

        void step(String label);

        void evidence(AgentResponse.Evidence evidence);

        void answer(AgentResponse response);
    }

    private static final Logger log = LoggerFactory.getLogger(ChatAnswerService.class);
    private static final String RUSSIAN = "ru";
    private static final String UZBEK = "uz";

    private final AgentGate agent;
    private final ChatRepository chats;
    private final int historyTurns;

    public ChatAnswerService(AgentGate agent, ChatRepository chats, AgentProperties properties) {
        this.agent = agent;
        this.chats = chats;
        this.historyTurns = properties.historyTurns();
    }

    public void answer(String question, Optional<UUID> user, Optional<ChatSummary> existing, String requestedLang, Events events) {
        String lang = existing.map(ChatSummary::lang).orElseGet(() -> languageFor(requestedLang, question));
        Optional<ChatSummary> chat = existing.isPresent() ? existing : startChat(user, lang, question);
        chat.ifPresent(events::chat);
        List<AgentService.Turn> history = chat.map(c -> history(c.id())).orElse(List.of());
        AgentResponse response = agent.ask(question, history, RUSSIAN.equals(lang) ? Lang.RU : Lang.UZ_LATN, progressTo(events));
        chat.ifPresent(c -> save(c.id(), question, response));
        events.answer(response);
    }

    private static ProgressListener progressTo(Events events) {
        return new ProgressListener() {
            @Override
            public void step(String label) {
                events.step(label);
            }

            @Override
            public void evidence(AgentResponse.Evidence evidence) {
                events.evidence(evidence);
            }
        };
    }

    private static String languageFor(String requested, String question) {
        if (RUSSIAN.equals(requested) || UZBEK.equals(requested)) {
            return requested;
        }
        return Lang.detect(question) == Lang.RU ? RUSSIAN : UZBEK;
    }

    private Optional<ChatSummary> startChat(Optional<UUID> user, String lang, String question) {
        try {
            return user.map(u -> chats.create(u, lang, question));
        } catch (RuntimeException e) {
            log.error("Could not create a chat, answering without saving", e);
            return Optional.empty();
        }
    }

    private List<AgentService.Turn> history(UUID chat) {
        try {
            return chats.history(chat, historyTurns);
        } catch (RuntimeException e) {
            log.error("Could not read chat history, answering without it", e);
            return List.of();
        }
    }

    private void save(UUID chat, String question, AgentResponse response) {
        try {
            chats.addTurn(chat, question, response);
        } catch (RuntimeException e) {
            log.error("Could not save the answer", e);
        }
    }
}
