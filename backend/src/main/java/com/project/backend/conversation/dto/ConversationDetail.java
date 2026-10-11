package com.project.backend.conversation.dto;

import java.util.List;

import com.project.backend.conversation.ProductCategory;

public record ConversationDetail(Long id, String title, ProductCategory category, List<MessageResponse> messages) {
}
