package com.project.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "message는 필수입니다.")
        @Size(max = 4000, message = "message는 4000자 이하여야 합니다.")
        String message,
        @Positive(message = "conversationId는 양수여야 합니다.")
        Long conversationId
) {
}
