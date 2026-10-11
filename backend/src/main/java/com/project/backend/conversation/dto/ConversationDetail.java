package com.project.backend.conversation.dto;

import java.util.List;

public record ConversationDetail(Long id, String title, List<MessageResponse> messages) {
}
