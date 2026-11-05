package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.uzex.agent.identity.AccountService;
import uz.uzex.agent.identity.Credentials;

/** Optional accounts: the page works without any of this. */
@RestController
public class AuthController {

    public record AuthRequest(String username, String password) {
        /** Never prints the password, for example when a request ends up in a log. */
        @Override
        public String toString() {
            return "AuthRequest[username=" + username + "]";
        }
    }

    private static final Map<String, String> TOO_MANY = Map.of("error", "too_many_attempts");

    private final AccountService accounts;
    private final CurrentUser currentUser;
    /** Every attempt from one address, and guesses at one username from any address. */
    private final RateLimiter byAddress = new RateLimiter(30, Duration.ofMinutes(1));
    private final RateLimiter byUsername = new RateLimiter(10, Duration.ofMinutes(5));

    public AuthController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Map<String, Object> me = new HashMap<>();
        me.put("username", currentUser.find(request).flatMap(accounts::usernameOf).orElse(null));
        return me;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody AuthRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (!byAddress.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(TOO_MANY);
        }
        Credentials credentials = Credentials.forRegistration(body.username(), body.password());
        UUID user = accounts.register(credentials, currentUser.find(request));
        currentUser.signInAs(user, request, response);
        return ResponseEntity.ok(Map.of("username", credentials.username()));
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody AuthRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username = Optional.ofNullable(body.username()).orElse("").strip().toLowerCase(Locale.ROOT);
        if (!byAddress.allow(request.getRemoteAddr()) || !byUsername.allow(username)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(TOO_MANY);
        }
        UUID user = accounts.signIn(body.username(), body.password(), currentUser.find(request));
        currentUser.signInAs(user, request, response);
        return ResponseEntity.ok(Map.of("username", accounts.usernameOf(user).orElse("")));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        currentUser.signOut(request, response);
        return ResponseEntity.noContent().build();
    }
}
