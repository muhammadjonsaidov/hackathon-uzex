package uz.uzex.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.uzex.agent.chat.ChatRepository;
import uz.uzex.agent.chat.ChatSummary;

@RestController
public class ChatController {

    public record ChatDetails(ChatSummary chat, List<ChatRepository.StoredTurn> turns) {}

    private final CurrentUser currentUser;
    private final ChatRepository chats;

    public ChatController(CurrentUser currentUser, ChatRepository chats) {
        this.currentUser = currentUser;
        this.chats = chats;
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
}
