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
 * require the prototype to work without registration.
 */
@Service
public class AccountService {

    private static final String SESSION_COOKIE = "uzex_session";
    private static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public AccountService(AppDatabase database) {
        this.jdbc = database.jdbc();
    }

    /** The user behind this browser, if it already has a valid session. */
    public Optional<UUID> find(HttpServletRequest request) {
        String token = cookie(request);
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

    private void startSession(UUID user, HttpServletRequest request, HttpServletResponse response) {
        String token = newToken();
        jdbc.sql("INSERT INTO web_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
                .params(hash(token), user, Timestamp.from(Instant.now().plus(SESSION_LIFETIME)))
                .update();
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(SESSION_LIFETIME)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(SESSION_COOKIE)) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
