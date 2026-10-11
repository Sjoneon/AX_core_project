package com.project.backend.conversation.dto;

import java.time.Instant;

import com.project.backend.conversation.Conversation;

public record ConversationSummary(Long id, String title, Instant createdAt, Instant updatedAt) {
    public static ConversationSummary from(Conversation conversation) {
        return new ConversationSummary(conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
