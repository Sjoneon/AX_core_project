package com.project.backend.conversation.dto;

import java.util.List;

import com.project.backend.conversation.ProductCategory;

public record ChatContext(ProductCategory category, List<HistoryMessage> messages) {
    public record HistoryMessage(String role, String content) {
    }
}
