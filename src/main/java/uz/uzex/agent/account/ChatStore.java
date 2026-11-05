package uz.uzex.agent.account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.uzex.agent.agent.AgentResponse;
import uz.uzex.agent.agent.AgentService;

/** Chats and their turns. Every read of a chat is scoped to its owner. */
@Service
public class ChatStore {

    public record ChatSummary(UUID id, String title, String lang, long updatedAt) {}

    public record StoredTurn(String question, JsonNode response) {}

    private static final int MAX_LISTED_CHATS = 100;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final String SUMMARY_COLUMNS = "id, title, lang, updated_at";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public ChatStore(AppDatabase database) {
        this.jdbc = database.jdbc();
    }

    public List<ChatSummary> list(UUID user) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM chats WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?")
                .params(user, MAX_LISTED_CHATS)
                .query(ChatStore::summary)
                .list();
    }

    public Optional<ChatSummary> find(UUID user, UUID chat) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM chats WHERE id = ? AND user_id = ?")
                .params(chat, user)
                .query(ChatStore::summary)
                .optional();
    }

    public ChatSummary create(UUID user, String lang, String firstQuestion) {
        UUID id = UUID.randomUUID();
        String title = firstQuestion.length() <= MAX_TITLE_LENGTH ? firstQuestion : firstQuestion.substring(0, MAX_TITLE_LENGTH);
        jdbc.sql("INSERT INTO chats (id, user_id, lang, title) VALUES (?, ?, ?, ?)")
                .params(id, user, lang, title)
                .update();
        return find(user, id).orElseThrow();
    }

    /** Callers must have checked ownership with {@link #find}. */
    public List<StoredTurn> turns(UUID chat) {
        return jdbc.sql("SELECT question, response::text FROM turns WHERE chat_id = ? ORDER BY id")
                .param(chat)
                .query((rs, n) -> new StoredTurn(rs.getString(1), JSON.readTree(rs.getString(2))))
                .list();
    }

    /** The latest answered turns, oldest first, as context for the model. */
    public List<AgentService.Turn> history(UUID chat, int limit) {
        List<AgentService.Turn> recent = new ArrayList<>(jdbc.sql("""
                SELECT question, response ->> 'answer' FROM turns
                WHERE chat_id = ? AND response ->> 'status' <> 'ERROR'
                ORDER BY id DESC LIMIT ?
                """)
                .params(chat, limit)
                .query((rs, n) -> new AgentService.Turn(rs.getString(1), rs.getString(2)))
                .list());
        Collections.reverse(recent);
        return recent;
    }

    public void addTurn(UUID chat, String question, AgentResponse response) {
        jdbc.sql("INSERT INTO turns (chat_id, question, response) VALUES (?, ?, CAST(? AS jsonb))")
                .params(chat, question, JSON.writeValueAsString(response))
                .update();
        jdbc.sql("UPDATE chats SET updated_at = now() WHERE id = ?").param(chat).update();
    }

    private static ChatSummary summary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ChatSummary(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getTimestamp(4).getTime());
    }
}
