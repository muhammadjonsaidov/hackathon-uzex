package uz.uzex.agent.answering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uz.uzex.agent.answering.AnswerVerifier.Sources;
import uz.uzex.agent.answering.AnswerVerifier.Verdict;

class AnswerVerifierTest {

    private final AnswerVerifier verifier = new AnswerVerifier();

    private Verdict check(String answer, String... cells) {
        return verifier.verify(answer, new Sources(List.of(cells), "вопрос", "Мука, Исполнен"));
    }

    @Test
    void acceptsExactNumberWithSpacedThousands() {
        assertThat(check("Всего 115 763 сделки.", "115763").ok()).isTrue();
    }

    @Test
    void acceptsNonBreakingSpacesAndDecimalComma() {
        assertThat(check("Сумма 304 547 938,40 сум.", "304547938.4").ok()).isTrue();
    }

    @Test
    void acceptsRoundingToShownPrecision() {
        assertThat(check("Цена 3 820,07 сум за кг.", "3820.0666").ok()).isTrue();
        assertThat(check("Итого 14,7 трлн сум.", "14738418674737.6").ok()).isTrue();
        assertThat(check("Итого 14738 млрд сум.", "14738418674737.6").ok()).isTrue();
    }

    @Test
    void rejectsInventedNumber() {
        Verdict verdict = check("Всего 115 764 сделки.", "115763");
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.unknownNumbers()).containsExactly("115 764");
    }

    @Test
    void rejectsWrongRounding() {
        assertThat(check("Итого 14,8 трлн сум.", "14738418674737.6").ok()).isFalse();
    }

    @Test
    void rejectsNumberComputedByTheModel() {
        Verdict verdict = check("Сумма по двум годам: 300.", "100", "150");
        assertThat(verdict.ok()).isFalse();
    }

    @Test
    void allowsListNumberingAndDataYears() {
        assertThat(check("1. Мука\n2. Рис\nЗа 2025 год.", "Мука").ok()).isTrue();
    }

    @Test
    void doesNotAllowYearsOutsideTheData() {
        assertThat(check("В 2019 году сделок нет.", "0").ok()).isFalse();
    }

    @Test
    void allowsYearsMentionedInTheQuestion() {
        Verdict verdict = verifier.verify("В 2019 году данных нет.", new Sources(List.of(), "Сколько сделок в 2019?", ""));
        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void checksDatesAgainstEvidenceInEitherFormat() {
        assertThat(check("Сделка заключена 24.06.2026.", "2026-06-24").ok()).isTrue();
        Verdict wrong = check("Сделка заключена 25.06.2026.", "2026-06-24");
        assertThat(wrong.ok()).isFalse();
        assertThat(wrong.unknownDates()).containsExactly("25.06.2026");
    }

    @Test
    void allowsCoverageDatesEverywhere() {
        assertThat(check("Данные охватывают период с 03.01.2022 по 30.10.2026.").ok()).isTrue();
    }

    @Test
    void acceptsCalendarDaysWrittenWithAMonth() {
        assertThat(check("С 1 января по 31 марта 2025 года: 328 сделок.", "328").ok()).isTrue();
        assertThat(check("2025-yil 31-martgacha 328 ta bitim.", "328").ok()).isTrue();
    }

    @Test
    void stillRejectsTheSameNumberWithoutAMonth() {
        assertThat(check("Всего 31 сделка.", "328").ok()).isFalse();
    }

    @Test
    void rejectsCreditingAnOutsideSource() {
        Verdict verdict = check("Курс 11 758,12 сум по данным Центрального банка.", "11758.12");
        assertThat(verdict.ok()).isFalse();
        assertThat(check("Kurs Markaziy bankning rasmiy kursi bo‘yicha olindi.").ok()).isFalse();
    }

    @Test
    void allowsAnOutsideSourceTheQuestionItselfMentions() {
        Verdict verdict = verifier.verify("Курса Центрального банка в данных нет.",
                new Sources(List.of(), "Какой курс Центрального банка?", ""));
        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void noAnswerRepliesMayNotNameOtherProducts() {
        List<String> terms = List.of("АИ-80", "АИ-92", "Мука", "Рис");
        assertThat(AnswerVerifier.namedBeyondQuestion("Есть только АИ-80 и АИ-92.", terms, "Цена АИ-98?"))
                .containsExactly("АИ-80", "АИ-92");
        assertThat(AnswerVerifier.namedBeyondQuestion("Марки АИ-98 в данных нет.", terms, "Цена АИ-98?")).isEmpty();
    }

    @Test
    void noAnswerRepliesMayRepeatWhatTheQuestionNamed() {
        assertThat(AnswerVerifier.namedBeyondQuestion("Причин роста цен на АИ-92 в данных нет.",
                List.of("АИ-92"), "Почему подорожал АИ-92?")).isEmpty();
    }

    @Test
    void noAnswerCheckDoesNotMatchInsideOtherWords() {
        assertThat(AnswerVerifier.namedBeyondQuestion("Рисков и прогнозов в данных нет.", List.of("Рис"), "Какие риски?"))
                .isEmpty();
    }

    @Test
    void noAnswerRepliesMayNotOfferASimilarCompany() {
        List<String> candidates = List.of("\"Sheto‘zo Industriya\" MChJ");
        assertThat(AnswerVerifier.namedBeyondQuestion("Похожая компания: \"Sheto'zo Industriya\" MChJ.",
                candidates, "Сколько продала «Sheto‘zo Kimyo» MChJ?")).hasSize(1);
    }

    @Test
    void ignoresDigitsInsideProductCodes() {
        assertThat(check("Бензин АИ-92 и марка PP-2310 продавались.").ok()).isTrue();
    }

    @Test
    void acceptsCompanyNamesFromEvidenceWithAnyApostrophe() {
        assertThat(check("Покупатель: \"TO'HER GROUP MCHJ\".", "TO‘HER GROUP MCHJ").ok()).isTrue();
    }

    @Test
    void rejectsInventedQuotedCompany() {
        Verdict verdict = check("Крупнейший покупатель: «Фантом Трейд».", "TO‘HER GROUP MCHJ");
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.unknownNames()).containsExactly("Фантом Трейд");
    }

    @Test
    void rejectsInventedAllCapsCompany() {
        assertThat(check("Крупнейший покупатель FANTOM SAVDO.", "TO‘HER GROUP MCHJ").ok()).isFalse();
    }

    @Test
    void readsInnsAndRefusesUnknownOnes() {
        assertThat(check("ИНН 908670023.", "908670023").ok()).isTrue();
        assertThat(check("ИНН 908670024.", "908670023").ok()).isFalse();
    }
}
