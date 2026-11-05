package uz.uzex.agent.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.uzex.agent.persistence.AppDatabase;

@Repository
public class UserRepository {

    public record StoredUser(UUID id, String username, String passwordHash) {
        public boolean isGuest() {
            return username == null;
        }
    }

    private final JdbcClient jdbc;

    public UserRepository(AppDatabase database) {
        this.jdbc = database.jdbc();
    }

    public UUID createGuest() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id) VALUES (?)").param(id).update();
        return id;
    }

    public Optional<StoredUser> find(UUID id) {
        return jdbc.sql("SELECT id, username, password_hash FROM users WHERE id = ?")
                .param(id)
                .query(UserRepository::toUser)
                .optional();
    }

    public Optional<StoredUser> findByUsername(String username) {
        return jdbc.sql("SELECT id, username, password_hash FROM users WHERE lower(username) = lower(?)")
                .param(username)
                .query(UserRepository::toUser)
                .optional();
    }

    /** Turns a guest into a registered user, keeping its id and so its chats. */
    public void setCredentials(UUID id, String username, String passwordHash) {
        jdbc.sql("UPDATE users SET username = ?, password_hash = ? WHERE id = ?")
                .params(username, passwordHash, id)
                .update();
    }

    public int deleteGuestsOlderThan(Duration age) {
        return jdbc.sql("DELETE FROM users WHERE username IS NULL AND created_at < ?")
                .param(Timestamp.from(Instant.now().minus(age)))
                .update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }

    private static StoredUser toUser(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StoredUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3));
    }
}
