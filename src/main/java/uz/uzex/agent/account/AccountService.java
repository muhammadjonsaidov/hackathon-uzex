package uz.uzex.agent.account;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Who is asking. Everyone gets an anonymous user behind an HttpOnly cookie, because the rules
 * require the prototype to work without registration. Telegram login attaches to that same user.
 */
@Service
public class AccountService {

    public record Profile(String name, String username, boolean telegram) {}

    private static final String SESSION_COOKIE = "uzex_session";
    private static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AppDatabase database;

    public AccountService(AppDatabase database) {
        this.database = database;
        this.jdbc = database.jdbc();
    }

    /** The user behind this browser, if it already has a valid session. */
    public Optional<UUID> find(HttpServletRequest request) {
        String token = cookie(request, SESSION_COOKIE);
        if (token == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT user_id FROM web_sessions WHERE token_hash = ? AND expires_at > now()")
                .param(hash(token))
                .query(UUID.class)
                .optional();
    }

    /** The user behind this browser, creating an anonymous one on first use. */
    public UUID require(HttpServletRequest request, HttpServletResponse response) {
        Optional<UUID> existing = find(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID user = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id) VALUES (?)").param(user).update();
        startSession(user, request, response);
        return user;
    }

    public Optional<Profile> profile(UUID user) {
        return jdbc.sql("SELECT telegram_name, telegram_username, telegram_id FROM users WHERE id = ?")
                .param(user)
                .query((rs, n) -> new Profile(rs.getString(1), rs.getString(2), rs.getObject(3) != null))
                .optional();
    }

    /**
     * Links a verified Telegram account. An anonymous browser's chats move to that account, and
     * the session token is replaced so a token known before login stops working.
     */
    public void signInWithTelegram(TelegramVerifier.Identity identity, HttpServletRequest request, HttpServletResponse response) {
        UUID target = database.transactions().execute(status -> {
            UUID current = find(request).orElse(null);
            Optional<UUID> owner = jdbc.sql("SELECT id FROM users WHERE telegram_id = ?")
                    .param(identity.telegramId())
                    .query(UUID.class)
                    .optional();
            UUID account = owner.orElse(null);
            boolean currentIsAnonymous = current != null && isAnonymous(current);
            if (account == null) {
                if (currentIsAnonymous) {
                    account = current;
                } else {
                    account = UUID.randomUUID();
                    jdbc.sql("INSERT INTO users (id) VALUES (?)").param(account).update();
                }
            } else if (currentIsAnonymous && !current.equals(account)) {
                jdbc.sql("UPDATE chats SET user_id = ? WHERE user_id = ?").params(account, current).update();
                jdbc.sql("DELETE FROM users WHERE id = ?").param(current).update();
            }
            jdbc.sql("UPDATE users SET telegram_id = ?, telegram_username = ?, telegram_name = ? WHERE id = ?")
                    .params(identity.telegramId(), identity.username(), identity.name(), account)
                    .update();
            return account;
        });
        endSession(request);
        startSession(target, request, response);
    }

    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        endSession(request);
        addCookie(response, request, SESSION_COOKIE, "", Duration.ZERO);
    }

    /** A random value for this browser that the next Telegram token must carry, to stop replays. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(name)) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public static void addCookie(HttpServletResponse response, HttpServletRequest request, String name, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean isAnonymous(UUID user) {
        return jdbc.sql("SELECT telegram_id IS NULL FROM users WHERE id = ?")
                .param(user)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private void startSession(UUID user, HttpServletRequest request, HttpServletResponse response) {
        String token = newToken();
        jdbc.sql("INSERT INTO web_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
                .params(hash(token), user, Timestamp.from(Instant.now().plus(SESSION_LIFETIME)))
                .update();
        addCookie(response, request, SESSION_COOKIE, token, SESSION_LIFETIME);
    }

    private void endSession(HttpServletRequest request) {
        String token = cookie(request, SESSION_COOKIE);
        if (token != null) {
            jdbc.sql("DELETE FROM web_sessions WHERE token_hash = ?").param(hash(token)).update();
        }
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
