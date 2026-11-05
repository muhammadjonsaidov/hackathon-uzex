package uz.uzex.agent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialsTest {

    private static final String GOOD_PASSWORD = "correct-horse";

    @ParameterizedTest
    @ValueSource(strings = {"abc", "user_01", "first.last", "a-b-c", "ABC123"})
    void acceptsReasonableUsernames(String username) {
        assertThat(Credentials.forRegistration(username, GOOD_PASSWORD).username()).isEqualTo(username);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "has space", "<script>", "имя", "o‘zbek", "a23456789012345678901234567890123"})
    void refusesOtherUsernames(String username) {
        assertThatThrownBy(() -> Credentials.forRegistration(username, GOOD_PASSWORD))
                .isInstanceOfSatisfying(AccountException.class,
                        e -> assertThat(e.reason()).isEqualTo(AccountException.Reason.INVALID_USERNAME));
    }

    @Test
    void refusesShortAndOverlongPasswords() {
        for (String password : new String[] {null, "", "short12", "x".repeat(73), "я".repeat(37)}) {
            assertThatThrownBy(() -> Credentials.forRegistration("user_01", password))
                    .isInstanceOfSatisfying(AccountException.class,
                            e -> assertThat(e.reason()).isEqualTo(AccountException.Reason.WEAK_PASSWORD));
        }
    }

    @Test
    void acceptsPasswordsAtTheLimits() {
        assertThat(Credentials.forRegistration("user_01", "x".repeat(8))).isNotNull();
        assertThat(Credentials.forRegistration("user_01", "x".repeat(72))).isNotNull();
    }

    @Test
    void neverPrintsThePassword() {
        assertThat(Credentials.forRegistration("user_01", GOOD_PASSWORD).toString())
                .contains("user_01")
                .doesNotContain(GOOD_PASSWORD);
    }
}
