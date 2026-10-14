package com.project.backend.conversation.dto;

import java.time.Instant;
import java.util.List;

import com.project.backend.conversation.ChatMessage;
import com.project.backend.rag.RagSource;
import com.project.backend.warranty.WarrantyModels;

public record MessageResponse(Long id, String role, String content, Instant createdAt, List<RagSource> sources, WarrantyModels.Result warranty,
        java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> tools) {
    public static MessageResponse from(ChatMessage message) {
        return new MessageResponse(message.getId(), message.getRole(), message.getContent(), message.getCreatedAt(), message.getSources(), message.getWarranty(),message.getTools());
    }
}
