package com.project.backend.chat.dto;

import java.util.List;

import com.project.backend.rag.RagSource;

public record ChatResponse(String reply, Long conversationId, List<RagSource> sources) {
}
