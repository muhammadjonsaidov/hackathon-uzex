package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.uzex.agent.chat.ChatRepository;
import uz.uzex.agent.chat.ChatSummary;
import uz.uzex.agent.chat.ChatTitle;
import uz.uzex.agent.identity.AccountService;

@RestController
public class ChatController {

    public record ChatDetails(ChatSummary chat, List<ChatRepository.StoredTurn> turns) {}

    public record RenameRequest(String title) {}

    private final CurrentUser currentUser;
    private final ChatRepository chats;
    private final AccountService accounts;

    public ChatController(CurrentUser currentUser, ChatRepository chats, AccountService accounts) {
        this.currentUser = currentUser;
        this.chats = chats;
        this.accounts = accounts;
    }

    @GetMapping("/api/chats")
    public List<ChatSummary> chats(HttpServletRequest request) {
        return currentUser.find(request).map(chats::list).orElse(List.of());
    }

    /** Someone else's chat answers exactly like a chat that does not exist. */
    @GetMapping("/api/chats/{id}")
    public ResponseEntity<ChatDetails> chat(@PathVariable UUID id, HttpServletRequest request) {
        return currentUser.find(request)
                .flatMap(user -> chats.find(user, id))
                .map(chat -> ResponseEntity.ok(new ChatDetails(chat, chats.turns(chat.id()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/api/chats/{id}")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestBody RenameRequest body, HttpServletRequest request) {
        Optional<String> title = ChatTitle.fromInput(body.title());
        if (title.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        boolean renamed = currentUser.find(request).map(user -> chats.rename(user, id, title.get())).orElse(false);
        return renamed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/api/chats/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        boolean deleted = currentUser.find(request).map(user -> chats.delete(user, id)).orElse(false);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Guest chats last until the page is reloaded. Registered users are not affected. */
    @PostMapping("/api/chats/clear-guest")
    public ResponseEntity<Void> clearGuest(HttpServletRequest request) {
        currentUser.find(request).filter(accounts::isGuest).ifPresent(chats::deleteAllOf);
        return ResponseEntity.noContent().build();
    }
}
