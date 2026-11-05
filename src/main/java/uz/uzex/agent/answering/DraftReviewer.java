package uz.uzex.agent.answering;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.uzex.agent.answering.AnswerVerifier.Sources;
import uz.uzex.agent.answering.AnswerVerifier.Verdict;
import uz.uzex.agent.answering.DraftParser.Draft;
import uz.uzex.agent.answering.DraftParser.Status;

/** Decides whether the model's draft may be shown, and says what is wrong with it if not. */
@Component
public class DraftReviewer {

    private final AnswerVerifier verifier;
    private final PromptSource prompt;

    public DraftReviewer(AnswerVerifier verifier, PromptSource prompt) {
        this.verifier = verifier;
        this.prompt = prompt;
    }

    /** The problem to send back to the model, or empty when the draft may be shown. */
    public Optional<String> review(Draft draft, String question, Trace trace) {
        if (draft.answer().isBlank()) {
            return Optional.of("the answer is empty");
        }
        Verdict verdict = verify(draft, question, trace);
        if (!verdict.ok()) {
            return Optional.of(draft.status() == Status.NO_DATA
                    ? "a NO_DATA answer may only say what is missing and the data coverage, with no figures or company details: "
                            + verdict.describe()
                    : verdict.describe());
        }
        return switch (draft.status()) {
            case ANSWER -> trace.hasSqlRows() || trace.hasLookupRows()
                    ? Optional.empty()
                    : Optional.of("status ANSWER needs a tool result with rows, and there is none");
            case CLARIFY -> trace.hasLookupRows()
                    ? Optional.empty()
                    : Optional.of("status CLARIFY needs candidates from find_company or find_broker");
            case NO_DATA -> namesBeyondQuestion(draft, question, trace);
            case GLOSSARY -> Optional.empty();
        };
    }

    private Verdict verify(Draft draft, String question, Trace trace) {
        // "No answer" must stand alone. A figure beside it is a figure where the data has no answer,
        // even when a query returned it, so only the question and the coverage dates are allowed.
        List<String> evidence = draft.status() == Status.NO_DATA ? List.of() : trace.evidenceCells();
        return verifier.verify(fullText(draft), new Sources(evidence, question, prompt.referenceText()));
    }

    private Optional<String> namesBeyondQuestion(Draft draft, String question, Trace trace) {
        String text = fullText(draft);
        List<String> named = new ArrayList<>(AnswerVerifier.namedBeyondQuestion(text, prompt.productTerms(), question));
        named.addAll(AnswerVerifier.namedBeyondQuestion(text, trace.lookupNames(), question));
        return named.isEmpty()
                ? Optional.empty()
                : Optional.of("a NO_DATA answer may not name products or companies the question did not name, even real ones: " + named);
    }

    private static String fullText(Draft draft) {
        return draft.answer() + "\n" + String.join("\n", draft.assumptions());
    }
}
