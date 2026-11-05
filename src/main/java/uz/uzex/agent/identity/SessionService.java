package uz.uzex.agent.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import uz.uzex.agent.persistence.AppDatabase;

/** Browser sessions. Only a hash of each token is stored, so a leaked table cannot log anyone in. */
@Service
public class SessionService {

    public static final Duration LIFETIME = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbc;

    public SessionService(AppDatabase database) {
        this.jdbc = database.jdbc();
    }

    public Optional<UUID> userFor(String token) {
        return jdbc.sql("SELECT user_id FROM web_sessions WHERE token_hash = ? AND expires_at > now()")
                .param(hash(token))
                .query(UUID.class)
                .optional();
    }

    /** Starts a session and returns its token, which is never stored. */
    public String start(UUID user) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("INSERT INTO web_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
                .params(hash(token), user, Timestamp.from(Instant.now().plus(LIFETIME)))
                .update();
        return token;
    }

    public void end(String token) {
        jdbc.sql("DELETE FROM web_sessions WHERE token_hash = ?").param(hash(token)).update();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
