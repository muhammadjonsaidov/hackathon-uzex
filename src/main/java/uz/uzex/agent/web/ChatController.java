package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.uzex.agent.account.AccountService;
import uz.uzex.agent.account.ChatStore;

@RestController
public class ChatController {

    public record ChatDetails(ChatStore.ChatSummary chat, List<ChatStore.StoredTurn> turns) {}

    private final AccountService accounts;
    private final ChatStore chats;

    public ChatController(AccountService accounts, ChatStore chats) {
        this.accounts = accounts;
        this.chats = chats;
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
