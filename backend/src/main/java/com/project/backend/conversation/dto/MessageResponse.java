package com.project.backend.conversation.dto;

import java.time.Instant;

import com.project.backend.conversation.ChatMessage;

public record MessageResponse(Long id, String role, String content, Instant createdAt) {
    public static MessageResponse from(ChatMessage message) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(), message.getCreatedAt());
    }
}
