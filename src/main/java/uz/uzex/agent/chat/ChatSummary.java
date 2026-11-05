package uz.uzex.agent.chat;

import java.util.UUID;

public record ChatSummary(UUID id, String title, String lang, long updatedAt) {}
