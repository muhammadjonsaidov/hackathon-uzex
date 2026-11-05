package uz.uzex.agent.identity;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Guests who closed the page never come back to clear their chats, so old guests are removed here.
 * Their chats and sessions go with them.
 */
@Component
public class GuestCleanup {

    private static final Logger log = LoggerFactory.getLogger(GuestCleanup.class);
    private static final Duration KEEP_GUESTS_FOR = Duration.ofDays(1);
    private static final long EVERY_HOUR_MS = 3_600_000;

    private final UserRepository users;

    public GuestCleanup(UserRepository users) {
        this.users = users;
    }

    @Scheduled(fixedDelay = EVERY_HOUR_MS, initialDelay = EVERY_HOUR_MS)
    void removeOldGuests() {
        try {
            int removed = users.deleteGuestsOlderThan(KEEP_GUESTS_FOR);
            log.info("Removed {} old guests", removed);
        } catch (RuntimeException e) {
            log.warn("Could not remove old guests: {}", e.getMessage());
        }
    }
}
