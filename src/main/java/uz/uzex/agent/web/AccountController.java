package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.uzex.agent.account.AccountService;
import uz.uzex.agent.account.ChatStore;
import uz.uzex.agent.account.TelegramVerifier;

@RestController
public class AccountController {

    public record TelegramLogin(String idToken) {}

    public record ChatDetails(ChatStore.ChatSummary chat, List<ChatStore.StoredTurn> turns) {}

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    private static final String NONCE_COOKIE = "uzex_tg_nonce";
    private static final Duration NONCE_LIFETIME = Duration.ofMinutes(30);

    private final AccountService accounts;
    private final ChatStore chats;
    private final TelegramVerifier telegram;

    public AccountController(AccountService accounts, ChatStore chats, TelegramVerifier telegram) {
        this.accounts = accounts;
        this.chats = chats;
        this.telegram = telegram;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Map<String, Object> me = new HashMap<>();
        me.put("telegramClientId", telegram.configured() ? telegram.clientId() : null);
        me.put("user", accounts.find(request).flatMap(accounts::profile).filter(AccountService.Profile::telegram).orElse(null));
        return me;
    }

    @PostMapping("/api/auth/telegram/nonce")
    public Map<String, String> nonce(HttpServletRequest request, HttpServletResponse response) {
        String nonce = AccountService.newToken();
        AccountService.addCookie(response, request, NONCE_COOKIE, nonce, NONCE_LIFETIME);
        return Map.of("nonce", nonce);
    }

    @PostMapping("/api/auth/telegram")
    public ResponseEntity<Void> signIn(@RequestBody TelegramLogin login, HttpServletRequest request, HttpServletResponse response) {
        String nonce = AccountService.cookie(request, NONCE_COOKIE);
        AccountService.addCookie(response, request, NONCE_COOKIE, "", Duration.ZERO);
        try {
            accounts.signInWithTelegram(telegram.verify(login.idToken(), nonce), request, response);
            return ResponseEntity.noContent().build();
        } catch (TelegramVerifier.InvalidTokenException e) {
            log.warn("Telegram login rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
        accounts.signOut(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/chats")
    public List<ChatStore.ChatSummary> chats(HttpServletRequest request) {
        return accounts.find(request).map(chats::list).orElse(List.of());
    }

    /** Someone else's chat answers exactly like a chat that does not exist. */
    @GetMapping("/api/chats/{id}")
    public ResponseEntity<ChatDetails> chat(@PathVariable UUID id, HttpServletRequest request) {
        return accounts.find(request)
                .flatMap(user -> chats.find(user, id))
                .map(chat -> ResponseEntity.ok(new ChatDetails(chat, chats.turns(chat.id()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
