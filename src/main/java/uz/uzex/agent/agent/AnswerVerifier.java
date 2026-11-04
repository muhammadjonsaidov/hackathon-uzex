package uz.uzex.agent.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Last gate before an answer is shown: every number, date and quoted or all-caps name must
 * come from the query results, the question or the published reference text. An invented
 * figure disqualifies the team, so this check is code rather than a request to the LLM.
 */
@Component
public class AnswerVerifier {

    public record Verdict(boolean ok, List<String> unknownNumbers, List<String> unknownDates, List<String> unknownNames) {
        public String describe() {
            List<String> parts = new ArrayList<>();
            if (!unknownNumbers.isEmpty()) {
                parts.add("numbers not found in tool results: " + unknownNumbers);
            }
            if (!unknownDates.isEmpty()) {
                parts.add("dates not found in tool results: " + unknownDates);
            }
            if (!unknownNames.isEmpty()) {
                parts.add("names not found in tool results: " + unknownNames);
            }
            return String.join("; ", parts);
        }
    }

    /** Sources an answer may draw on. */
    public record Sources(List<String> evidenceCells, String question, String referenceText) {}

    private static final BigDecimal SMALL_INTEGER_LIMIT = BigDecimal.valueOf(12);
    private static final Set<Integer> DATA_YEARS = Set.of(2022, 2023, 2024, 2025, 2026);
    private static final Set<String> COVERAGE_DATES = Set.of("2022-01-03", "2026-10-30");

    private static final Pattern DATE = Pattern.compile(
            "(?<![\\d.])(?:(\\d{2})\\.(\\d{2})\\.(\\d{4})|(\\d{4})-(\\d{2})-(\\d{2}))(?!\\d|\\.\\d)");
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\d])(?<![\\p{L}]-)"
                    + "(\\d{1,3}(?:[ \\u00a0\\u202f\\u2009]\\d{3})+(?:[.,]\\d+)?"
                    + "|\\d{1,3}(?:,\\d{3}){2,}(?:\\.\\d+)?"
                    + "|\\d+(?:[.,]\\d+)?)"
                    + "(?:\\s*(тыс\\.?|тысяч\\S*|ming|минг|млн\\.?|миллион\\S*|mln\\.?|million|millions|млрд\\.?"
                    + "|миллиард\\S*|mlrd\\.?|milliard\\S*|billion|трлн\\.?|триллион\\S*|trln\\.?|trillion\\S*)(?![\\p{L}]))?");
    private static final Pattern MONTH_AFTER = Pattern.compile(
            "(?iU)\\s*-?\\s*(январ|феврал|март|апрел|май|мая|июн|июл|август|сентябр|октябр|ноябр|декабр"
                    + "|yanvar|fevral|mart|aprel|may|iyun|iyul|avgust|sentabr|sentyabr|oktabr|oktyabr|noyabr|dekabr)");
    private static final Pattern OUTSIDE_SOURCE = Pattern.compile(
            "(?iU)центральн\\S*\\s+банк\\S*|центробанк\\S*|markaziy\\s+bank\\S*|марказий\\s+банк\\S*"
                    + "|официальн\\S*|rasmiy\\S*|расмий\\S*|bloomberg|reuters");
    private static final Pattern QUOTED = Pattern.compile("[«\"“]([^»\"”\\n]{3,80})[»\"”]");
    private static final Pattern ALL_CAPS = Pattern.compile(
            "(?U)(?<![\\w‘’'])\\p{Lu}[\\p{Lu}‘’']{3,}(?![\\w‘’'])");

    public Verdict verify(String answer, Sources sources) {
        String text = answer == null ? "" : answer;
        Set<String> allowedDates = new HashSet<>(COVERAGE_DATES);
        Set<BigDecimal> allowedNumbers = new HashSet<>();
        List<String> sourceTexts = new ArrayList<>(sources.evidenceCells());
        sourceTexts.add(sources.question());

        for (String source : sourceTexts) {
            if (source != null) {
                collect(source, allowedNumbers, allowedDates);
            }
        }
        collect(sources.referenceText() == null ? "" : sources.referenceText(), allowedNumbers, new HashSet<>());
        for (String date : allowedDates) {
            allowedNumbers.add(new BigDecimal(date.substring(8)));
        }

        Set<String> unknownDates = new LinkedHashSet<>();
        Matcher dates = DATE.matcher(text);
        StringBuilder withoutDates = new StringBuilder();
        while (dates.find()) {
            String iso = iso(dates);
            if (!allowedDates.contains(iso)) {
                unknownDates.add(dates.group());
            }
            dates.appendReplacement(withoutDates, " ");
        }
        dates.appendTail(withoutDates);

        Set<String> unknownNumbers = new LinkedHashSet<>();
        Matcher numbers = NUMBER.matcher(withoutDates);
        while (numbers.find()) {
            if (isDayOfMonth(numbers, withoutDates)) {
                continue;
            }
            if (!numberAllowed(numbers.group(1), numbers.group(2), allowedNumbers)) {
                unknownNumbers.add(numbers.group().strip());
            }
        }

        List<String> unknownNames = unknownNames(text, sources, sourceTexts);
        return new Verdict(
                unknownNumbers.isEmpty() && unknownDates.isEmpty() && unknownNames.isEmpty(),
                List.copyOf(unknownNumbers), List.copyOf(unknownDates), unknownNames);
    }

    /** "31 марта" or "31-mart" names a calendar day rather than a figure from the data. */
    private static boolean isDayOfMonth(Matcher number, CharSequence text) {
        if (number.group(2) != null || !number.group(1).matches("\\d{1,2}")) {
            return false;
        }
        int day = Integer.parseInt(number.group(1));
        return day >= 1 && day <= 31 && MONTH_AFTER.matcher(text).region(number.end(), text.length()).lookingAt();
    }

    private List<String> unknownNames(String text, Sources sources, List<String> sourceTexts) {
        StringBuilder haystackBuilder = new StringBuilder(sources.referenceText() == null ? "" : sources.referenceText());
        for (String source : sourceTexts) {
            if (source != null) {
                haystackBuilder.append(' ').append(source);
            }
        }
        String haystack = looseText(haystackBuilder.toString());
        Set<String> unknown = new LinkedHashSet<>();
        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) {
            if (!haystack.contains(looseText(quoted.group(1)))) {
                unknown.add(quoted.group(1));
            }
        }
        Matcher caps = ALL_CAPS.matcher(text);
        while (caps.find()) {
            if (!haystack.contains(looseText(caps.group()))) {
                unknown.add(caps.group());
            }
        }
        // The data names no source for anything, so crediting one is an invention even with correct figures.
        String question = sources.question() == null ? "" : looseText(sources.question());
        Matcher claim = OUTSIDE_SOURCE.matcher(text);
        while (claim.find()) {
            if (!question.contains(looseText(claim.group()))) {
                unknown.add(claim.group());
            }
        }
        return List.copyOf(unknown);
    }

    private static void collect(String source, Set<BigDecimal> numbers, Set<String> dates) {
        // A plain numeric cell is the common case and must keep its full precision.
        try {
            numbers.add(new BigDecimal(source.strip()));
            return;
        } catch (NumberFormatException ignored) {
            // Text cell: fall through and scan it.
        }
        Matcher dateMatcher = DATE.matcher(source);
        StringBuilder rest = new StringBuilder();
        while (dateMatcher.find()) {
            dates.add(iso(dateMatcher));
            dateMatcher.appendReplacement(rest, " ");
        }
        dateMatcher.appendTail(rest);
        Matcher numberMatcher = NUMBER.matcher(rest);
        while (numberMatcher.find()) {
            for (BigDecimal candidate : candidates(numberMatcher.group(1))) {
                numbers.add(candidate.multiply(scale(numberMatcher.group(2))));
            }
        }
    }

    private static boolean numberAllowed(String digits, String scaleWord, Set<BigDecimal> allowed) {
        BigDecimal scale = scale(scaleWord);
        for (BigDecimal shown : candidates(digits)) {
            if (scale.compareTo(BigDecimal.ONE) == 0) {
                if (shown.scale() <= 0 && shown.abs().compareTo(SMALL_INTEGER_LIMIT) <= 0) {
                    return true;
                }
                if (shown.scale() <= 0 && DATA_YEARS.contains(shown.intValue())) {
                    return true;
                }
            }
            BigDecimal value = shown.multiply(scale);
            BigDecimal halfStep = BigDecimal.ONE.movePointLeft(Math.max(shown.scale(), 0))
                    .multiply(scale)
                    .divide(BigDecimal.valueOf(2));
            for (BigDecimal candidate : allowed) {
                if (candidate.subtract(value).abs().compareTo(halfStep) <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "1,234" could mean either value, so both readings are tried. */
    private static List<BigDecimal> candidates(String raw) {
        String compact = raw.replaceAll("[ \\u00a0\\u202f\\u2009]", "");
        List<BigDecimal> values = new ArrayList<>();
        if (compact.indexOf(',') != compact.lastIndexOf(',')) {
            values.add(new BigDecimal(compact.replace(",", "")));
        } else if (compact.contains(",")) {
            values.add(new BigDecimal(compact.replace(',', '.')));
            if (compact.matches("\\d{1,3},\\d{3}")) {
                values.add(new BigDecimal(compact.replace(",", "")));
            }
        } else {
            values.add(new BigDecimal(compact));
        }
        return values;
    }

    private static BigDecimal scale(String word) {
        if (word == null) {
            return BigDecimal.ONE;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.startsWith("тыс") || lower.equals("ming") || lower.equals("минг")) {
            return BigDecimal.valueOf(1_000);
        }
        if (lower.startsWith("млн") || lower.startsWith("миллион") || lower.startsWith("mln") || lower.startsWith("million")) {
            return BigDecimal.valueOf(1_000_000);
        }
        if (lower.startsWith("млрд") || lower.startsWith("миллиард") || lower.startsWith("mlrd")
                || lower.startsWith("milliard") || lower.equals("billion")) {
            return BigDecimal.valueOf(1_000_000_000);
        }
        return BigDecimal.valueOf(1_000_000_000_000L);
    }

    private static String iso(Matcher date) {
        return date.group(1) != null
                ? date.group(3) + "-" + date.group(2) + "-" + date.group(1)
                : date.group(4) + "-" + date.group(5) + "-" + date.group(6);
    }

    private static String looseText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[‘’ʻʼ`´']", "'")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
