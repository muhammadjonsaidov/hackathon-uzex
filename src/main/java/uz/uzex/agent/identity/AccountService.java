package uz.uzex.agent.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.uzex.agent.identity.AccountException.Reason;
import uz.uzex.agent.identity.UserRepository.StoredUser;
import uz.uzex.agent.persistence.AppDatabase;

/**
 * Optional accounts. Registering keeps a guest's chats. Signing in moves them to the account.
 * The rules require the prototype to work without registration, so nothing here is ever required.
 */
@Service
public class AccountService {

    private static final int BCRYPT_STRENGTH = 12;

    private final UserRepository users;
    private final GuestDataTransfer guestData;
    private final TransactionTemplate transactions;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    /** Compared against when a username is unknown, so response time does not reveal which exist. */
    private final String unknownUserHash = encoder.encode("no-account-has-this-password");

    public AccountService(UserRepository users, GuestDataTransfer guestData, AppDatabase database) {
        this.users = users;
        this.guestData = guestData;
        this.transactions = database.transactions();
    }

    /** Returns the registered user: the guest itself when there is one, otherwise a new user. */
    public UUID register(Credentials credentials, Optional<UUID> currentUser) {
        String hash = encoder.encode(credentials.password());
        try {
            return transactions.execute(status -> {
                UUID user = currentUser
                        .flatMap(users::find)
                        .filter(StoredUser::isGuest)
                        .map(StoredUser::id)
                        .orElseGet(users::createGuest);
                users.setCredentials(user, credentials.username(), hash);
                return user;
            });
        } catch (DuplicateKeyException e) {
            throw new AccountException(Reason.USERNAME_TAKEN);
        }
    }

    /** The same refusal for a wrong password and an unknown username, so names cannot be probed. */
    public UUID signIn(String username, String password, Optional<UUID> currentUser) {
        Optional<StoredUser> account = username == null ? Optional.empty() : users.findByUsername(username.strip());
        String expected = account.map(StoredUser::passwordHash).orElse(unknownUserHash);
        boolean matches = encoder.matches(password == null ? "" : password, expected);
        if (account.isEmpty() || !matches) {
            throw new AccountException(Reason.INVALID_CREDENTIALS);
        }
        UUID target = account.get().id();
        currentUser.flatMap(users::find)
                .filter(StoredUser::isGuest)
                .filter(guest -> !guest.id().equals(target))
                .ifPresent(guest -> absorbGuest(guest.id(), target));
        return target;
    }

    public Optional<String> usernameOf(UUID user) {
        return users.find(user).map(StoredUser::username);
    }

    private void absorbGuest(UUID guest, UUID account) {
        transactions.executeWithoutResult(status -> {
            guestData.moveAll(guest, account);
            users.delete(guest);
        });
    }
}
