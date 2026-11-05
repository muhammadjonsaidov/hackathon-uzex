package uz.uzex.agent.identity;

/** An account action refused for a reason the person can act on. */
public class AccountException extends RuntimeException {

    public enum Reason { INVALID_USERNAME, WEAK_PASSWORD, USERNAME_TAKEN, INVALID_CREDENTIALS }

    private final Reason reason;

    public AccountException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
