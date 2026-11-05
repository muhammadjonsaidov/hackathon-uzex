package uz.uzex.agent.answering;

import java.util.List;

/** The language model, whichever provider is behind it. */
@FunctionalInterface
public interface LlmGateway {

    enum Role { SYSTEM, USER, ASSISTANT }

    record Message(Role role, String text) {}

    /** Runs the conversation, letting the model call the given tools, and returns its final text. */
    String complete(List<Message> messages, AgentTools tools);
}
