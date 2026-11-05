package uz.uzex.agent.agent;

import java.util.regex.Pattern;

/** Picks the reply language from the question, so fixed messages never need the LLM. */
public enum Lang {
    RU("Russian",
            "В данных нет ответа на этот вопрос. Данные охватывают сделки с 03.01.2022 по 30.10.2026.",
            "Не удалось уложиться в отведённое время. Ответ не выдан, чтобы не показывать непроверенные данные. Попробуйте ещё раз.",
            "Языковая модель сейчас недоступна, поэтому ответа нет. Попробуйте ещё раз.",
            "Сервис сейчас занят. Попробуйте ещё раз через несколько секунд.",
            "Результат запроса к данным:",
            "Ищу компанию", "Ищу брокера", "Выполняю запрос", "Сверяю числа в ответе с данными", "Читаю вопрос"),
    UZ_LATN("Uzbek in Latin script",
            "Ma’lumotlarda bu savolga javob yo‘q. Ma’lumotlar 03.01.2022 dan 30.10.2026 gacha bo‘lgan bitimlarni qamrab oladi.",
            "Belgilangan vaqtga ulgurilmadi. Tekshirilmagan ma’lumot ko‘rsatilmasligi uchun javob berilmadi. Qaytadan urinib ko‘ring.",
            "Til modeli hozir mavjud emas, shuning uchun javob yo‘q. Qaytadan urinib ko‘ring.",
            "Xizmat hozir band. Bir necha soniyadan keyin qayta urinib ko‘ring.",
            "Ma’lumotlar bo‘yicha so‘rov natijasi:",
            "Kompaniya qidirilmoqda", "Broker qidirilmoqda", "So‘rov bajarilmoqda", "Javobdagi raqamlar tekshirilmoqda", "Savol o‘qilmoqda"),
    UZ_CYRL("Uzbek in Cyrillic script",
            "Маълумотларда бу саволга жавоб йўқ. Маълумотлар 03.01.2022 дан 30.10.2026 гача бўлган битимларни қамраб олади.",
            "Белгиланган вақтга улгурилмади. Текширилмаган маълумот кўрсатилмаслиги учун жавоб берилмади. Қайтадан уриниб кўринг.",
            "Тил модели ҳозир мавжуд эмас, шунинг учун жавоб йўқ. Қайтадан уриниб кўринг.",
            "Хизмат ҳозир банд. Бир неча сониядан кейин қайта уриниб кўринг.",
            "Маълумотлар бўйича сўров натижаси:",
            "Компания қидирилмоқда", "Брокер қидирилмоқда", "Сўров бажарилмоқда", "Жавобдаги рақамлар текширилмоқда", "Савол ўқилмоқда");

    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LOWER_CYRILLIC_WORD = Pattern.compile("(?U)\\b[а-яёўқғҳ]\\w*");
    private static final Pattern LOWER_LATIN_WORD = Pattern.compile("(?U)\\b[a-z]\\w*");
    private static final Pattern NAMES = Pattern.compile("(?U)«[^»]*»|\"[^\"]*\"|“[^”]*”|\\b\\p{Lu}{2,}\\b");
    private static final Pattern UZBEK_CYRILLIC = Pattern.compile(
            "(?iU)[ўқғҳ]|\\b(неча\\S*|битим\\S*|йил\\S*|сотувчи\\S*|харидор\\S*|учун|канча|энг|кайси|савдо)\\b");

    public final String promptName;
    public final String noData;
    public final String timeout;
    public final String llmError;
    public final String busy;
    public final String fallbackPrefix;
    public final String stepCompany;
    public final String stepBroker;
    public final String stepSql;
    public final String stepVerify;
    public final String stepThinking;

    Lang(String promptName, String noData, String timeout, String llmError, String busy, String fallbackPrefix,
            String stepCompany, String stepBroker, String stepSql, String stepVerify, String stepThinking) {
        this.promptName = promptName;
        this.noData = noData;
        this.timeout = timeout;
        this.llmError = llmError;
        this.busy = busy;
        this.fallbackPrefix = fallbackPrefix;
        this.stepCompany = stepCompany;
        this.stepBroker = stepBroker;
        this.stepSql = stepSql;
        this.stepVerify = stepVerify;
        this.stepThinking = stepThinking;
    }

    public static Lang detect(String question) {
        if (question == null) {
            return RU;
        }
        if (!CYRILLIC.matcher(question).find()) {
            return UZ_LATN;
        }
        // Names and codes keep their own script: "Moto Group" in a Russian question, "ЦЕМ I 42,5Н"
        // in an Uzbek one. Ordinary words start in lower case, so they show the sentence's language.
        int cyrillicWords = count(LOWER_CYRILLIC_WORD, question);
        int latinWords = count(LOWER_LATIN_WORD, question);
        if (latinWords > cyrillicWords) {
            return UZ_LATN;
        }
        // Uzbek letters inside a company name say nothing about the language of the sentence.
        String sentence = NAMES.matcher(question).replaceAll(" ");
        return UZBEK_CYRILLIC.matcher(sentence).find() ? UZ_CYRL : RU;
    }

    /**
     * Whether an answer opens in this language's script. Only the first line is read, because the
     * lines after it can be lists of data values that keep their own script.
     */
    public boolean matchesScript(String answer) {
        if (this == UZ_LATN || answer == null) {
            return true;
        }
        String firstLine = answer.strip().split("\\R", 2)[0];
        return count(LOWER_LATIN_WORD, firstLine) <= count(LOWER_CYRILLIC_WORD, firstLine);
    }

    private static int count(Pattern pattern, String text) {
        return (int) pattern.matcher(text).results().count();
    }
}
