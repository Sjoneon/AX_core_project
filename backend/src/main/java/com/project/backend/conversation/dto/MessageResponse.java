package com.project.backend.conversation.dto;

import java.time.Instant;
import java.util.List;

import com.project.backend.conversation.ChatMessage;
import com.project.backend.rag.RagSource;

public record MessageResponse(Long id, String role, String content, Instant createdAt, List<RagSource> sources) {
    public static MessageResponse from(ChatMessage message) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(), message.getCreatedAt(), message.getSources());
    }
}
