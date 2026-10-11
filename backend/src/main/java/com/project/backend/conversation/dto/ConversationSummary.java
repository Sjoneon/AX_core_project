package com.project.backend.conversation.dto;

import java.time.Instant;

import com.project.backend.conversation.Conversation;
import com.project.backend.conversation.ProductCategory;

public record ConversationSummary(Long id, String title, ProductCategory category, Instant createdAt, Instant updatedAt) {
    public static ConversationSummary from(Conversation conversation) {
        return new ConversationSummary(conversation.getId(), conversation.getTitle(), conversation.getCategory(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
