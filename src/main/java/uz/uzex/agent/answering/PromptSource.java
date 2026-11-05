package uz.uzex.agent.answering;

import java.util.List;

/** What the answering flow needs to know about the prompt and the data's vocabulary. */
public interface PromptSource {

    String forLanguage(Lang lang);

    /** Text an answer may take names and constants from, the same for every language. */
    String referenceText();

    /** Product names and brands, which a "no answer" reply must not name. */
    List<String> productTerms();
}
