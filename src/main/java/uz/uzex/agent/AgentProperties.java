package uz.uzex.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(
        String model,
        String apiKey,
        boolean disableThinking,
        int deadlineSeconds,
        int repairBudgetSeconds,
        int maxSqlCalls,
        int maxRows,
        int rowsForLlm,
        int sqlTimeoutSeconds,
        int maxConcurrent,
        int maxQuestionLength,
        int requestsPerMinute,
        int historyTurns) {

    public boolean llmConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"replace-me".equals(apiKey);
    }
}
