package uz.uzex.agent.answering;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import uz.uzex.agent.config.AgentProperties;

/** Any OpenAI-compatible API through Spring AI. Nothing else depends on Spring AI. */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;
    private final AgentProperties properties;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder, AgentProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public String complete(List<Message> messages, AgentTools tools) {
        List<org.springframework.ai.chat.messages.Message> converted =
                messages.stream().map(SpringAiLlmGateway::convert).toList();
        ChatResponse response = chatClient.prompt(new Prompt(converted, options()))
                .tools(tools)
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private static org.springframework.ai.chat.messages.Message convert(Message message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.text());
            case USER -> new UserMessage(message.text());
            case ASSISTANT -> new AssistantMessage(message.text());
        };
    }

    private OpenAiChatOptions options() {
        var builder = OpenAiChatOptions.builder().model(properties.model()).temperature(0.0);
        if (properties.disableThinking()) {
            // DeepSeek thinks by default, and then wants reasoning_content replayed on every tool turn.
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        return builder.build();
    }
}
