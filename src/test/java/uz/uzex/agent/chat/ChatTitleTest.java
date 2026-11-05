package uz.uzex.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatTitleTest {

    @Test
    void cleansWhatSomeoneTyped() {
        assertThat(ChatTitle.fromInput("  Цемент   за\t2025\n")).contains("Цемент за 2025");
    }

    @Test
    void refusesAnEmptyTitle() {
        assertThat(ChatTitle.fromInput(null)).isEmpty();
        assertThat(ChatTitle.fromInput("   \n\t ")).isEmpty();
    }

    @Test
    void refusesATitleThatIsTooLong() {
        assertThat(ChatTitle.fromInput("x".repeat(80))).isPresent();
        assertThat(ChatTitle.fromInput("x".repeat(81))).isEmpty();
    }

    @Test
    void aLongQuestionIsCutToFit() {
        assertThat(ChatTitle.fromQuestion("x".repeat(200))).hasSize(80);
    }

    @Test
    void keepsTagsAsPlainText() {
        assertThat(ChatTitle.fromInput("<b>Мой чат</b>")).contains("<b>Мой чат</b>");
    }
}
