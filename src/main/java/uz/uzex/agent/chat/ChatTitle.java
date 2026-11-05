package uz.uzex.agent.chat;

import java.util.Optional;

/** The rules for a chat's title, shared by new chats and renames. */
public final class ChatTitle {

    static final int MAX_LENGTH = 80;

    private ChatTitle() {}

    /** A title made from someone's first question always fits, cut short if needed. */
    static String fromQuestion(String question) {
        String clean = normalise(question);
        return clean.length() <= MAX_LENGTH ? clean : clean.substring(0, MAX_LENGTH);
    }

    /** A title someone typed: empty if nothing is left, or it is too long to be a title. */
    public static Optional<String> fromInput(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String clean = normalise(input);
        return clean.isEmpty() || clean.length() > MAX_LENGTH ? Optional.empty() : Optional.of(clean);
    }

    private static String normalise(String text) {
        return text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }
}
