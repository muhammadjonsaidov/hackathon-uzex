package uz.uzex.agent.agent;

import java.util.List;
import uz.uzex.agent.sql.QueryResult;

/**
 * What the page shows.
 *
 * @param status ANSWER, NO_DATA, CLARIFY, GLOSSARY or ERROR
 * @param verified true when code confirmed every figure against the evidence
 * @param source LLM when the worded answer passed, FALLBACK when it failed and the raw result
 *     is shown instead, SYSTEM for fixed messages
 */
public record AgentResponse(
        String status,
        String answer,
        List<String> assumptions,
        List<Evidence> evidence,
        boolean verified,
        String source,
        String language,
        String model,
        long durationMs) {

    public record Evidence(String kind, String input, String purpose, QueryResult result, String error) {}
}
