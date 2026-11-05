package uz.uzex.agent.answering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LangTest {

    @Test
    void detectsRussian() {
        assertThat(Lang.detect("Сколько сделок было в 2025 году?")).isEqualTo(Lang.RU);
        assertThat(Lang.detect("Сколько сделок у TOHER GROUP?")).isEqualTo(Lang.RU);
    }

    @Test
    void detectsUzbekLatin() {
        assertThat(Lang.detect("2025-yilda nechta bitim bo‘lgan?")).isEqualTo(Lang.UZ_LATN);
    }

    @Test
    void namesInTheOtherScriptDoNotChangeTheLanguage() {
        assertThat(Lang.detect("Сколько сделок у \"Moto Group\" MChJ?")).isEqualTo(Lang.RU);
        assertThat(Lang.detect("2026-yil sentyabrida ЦЕМ I 42,5Н sementining narxi qancha?")).isEqualTo(Lang.UZ_LATN);
        assertThat(Lang.detect("Исполнен(Частично) holati nimani anglatadi?")).isEqualTo(Lang.UZ_LATN);
    }

    @Test
    void uzbekLettersInACompanyNameDoNotMakeTheQuestionUzbek() {
        assertThat(Lang.detect("Дайте справку по компании \"ҲАРАЙЎМ КИМЁ\" МЧЖ: ИНН и регион.")).isEqualTo(Lang.RU);
        assertThat(Lang.detect("Сколько сделок у ҲАРАЙЎМ КИМЁ МЧЖ?")).isEqualTo(Lang.RU);
        assertThat(Lang.detect("\"ҲАРАЙЎМ КИМЁ\" МЧЖ қайси вилоятда жойлашган?")).isEqualTo(Lang.UZ_CYRL);
    }

    @Test
    void noticesAnAnswerInTheWrongScript() {
        assertThat(Lang.UZ_CYRL.matchesScript("2025-yilda eng ko‘p sotilgan mahsulot — Хлопок-сырец.")).isFalse();
        assertThat(Lang.UZ_CYRL.matchesScript("2025 йилда энг кўп сотилган маҳсулот — Хлопок-сырец.")).isTrue();
        assertThat(Lang.RU.matchesScript("На втором месте — Xorazm viloyati с суммой 690 сум.")).isTrue();
    }

    @Test
    void valuesInAListDoNotCountAsTheWrongScript() {
        assertThat(Lang.RU.matchesScript("Три региона-лидера:\n1. Toshkent shahri\n2. Namangan viloyati\n3. Xorazm viloyati")).isTrue();
        assertThat(Lang.UZ_LATN.matchesScript("Eng yirik tovar:\n1. Пшеница продовольственная")).isTrue();
    }

    @Test
    void aQuestionWithNoWordsFollowsTheChatLanguage() {
        assertThat(Lang.detect("BK001", Lang.RU)).isEqualTo(Lang.RU);
        assertThat(Lang.detect("TOHER GROUP?", Lang.RU)).isEqualTo(Lang.RU);
        assertThat(Lang.detect("БРОКЕР BK001?", Lang.UZ_LATN)).isEqualTo(Lang.UZ_LATN);
    }

    @Test
    void wordsInTheQuestionOutrankTheChatLanguage() {
        assertThat(Lang.detect("2025-yilda nechta bitim bor?", Lang.RU)).isEqualTo(Lang.UZ_LATN);
        assertThat(Lang.detect("Сколько сделок?", Lang.UZ_LATN)).isEqualTo(Lang.RU);
    }

    @Test
    void detectsUzbekCyrillic() {
        assertThat(Lang.detect("2025 йилда нечта битим бўлган?")).isEqualTo(Lang.UZ_CYRL);
        assertThat(Lang.detect("Неча битим бор?")).isEqualTo(Lang.UZ_CYRL);
    }
}
