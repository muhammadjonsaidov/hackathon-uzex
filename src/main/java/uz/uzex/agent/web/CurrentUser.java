package uz.uzex.agent.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import uz.uzex.agent.identity.SessionService;
import uz.uzex.agent.identity.UserRepository;

/** Connects HTTP requests to users through an HttpOnly session cookie. */
@Component
public class CurrentUser {

    private static final String COOKIE = "uzex_session";

    private final SessionService sessions;
    private final UserRepository users;

    public CurrentUser(SessionService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public Optional<UUID> find(HttpServletRequest request) {
        return token(request).flatMap(sessions::userFor);
    }

    /** The user behind this browser, creating a guest on first use. */
    public UUID require(HttpServletRequest request, HttpServletResponse response) {
        return find(request).orElseGet(() -> {
            UUID guest = users.createGuest();
            writeCookie(request, response, sessions.start(guest), SessionService.LIFETIME);
            return guest;
        });
    }

    /** Replaces the session, so a token known before signing in stops working afterwards. */
    public void signInAs(UUID user, HttpServletRequest request, HttpServletResponse response) {
        token(request).ifPresent(sessions::end);
        writeCookie(request, response, sessions.start(user), SessionService.LIFETIME);
    }

    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        token(request).ifPresent(sessions::end);
        writeCookie(request, response, "", Duration.ZERO);
    }

    private static Optional<String> token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) {
                    return Optional.of(cookie.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private static void writeCookie(HttpServletRequest request, HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
