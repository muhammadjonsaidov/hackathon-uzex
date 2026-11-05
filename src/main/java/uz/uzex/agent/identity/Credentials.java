package uz.uzex.agent.identity;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** A username and password that meet the rules. Creating one is the validation. */
public record Credentials(String username, String password) {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt only reads the first 72 bytes, so a longer password would give false confidence. */
    private static final int MAX_PASSWORD_BYTES = 72;

    public static Credentials forRegistration(String username, String password) {
        String name = username == null ? "" : username.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw new AccountException(AccountException.Reason.INVALID_USERNAME);
        }
        if (password == null
                || password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new AccountException(AccountException.Reason.WEAK_PASSWORD);
        }
        return new Credentials(name, password);
    }

    /** Never prints the password, for example when a record ends up in a log. */
    @Override
    public String toString() {
        return "Credentials[username=" + username + "]";
    }
}
