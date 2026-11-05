package uz.uzex.agent.identity;

import java.util.UUID;

/**
 * Moves what a guest created to the account they signed in to. Identity defines this and the owner
 * of that data implements it, so identity does not depend on chats.
 */
@FunctionalInterface
public interface GuestDataTransfer {

    void moveAll(UUID fromUser, UUID toUser);
}
