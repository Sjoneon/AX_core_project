package com.project.backend.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.project.backend.chat.dto.ChatRequest;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.chat.exception.ChatUnavailableException;
import com.project.backend.conversation.ConversationService;
import com.project.backend.conversation.ProductCategory;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ConversationService conversationService;

    public ChatService(ChatClient.Builder builder, ConversationService conversationService) {
        this.conversationService = conversationService;
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Reply in Korean unless the user asks for another language.")
                .build();
    }

    public ChatResponse chat(ChatRequest request, String email) {
        ProductCategory category = conversationService.resolveCategory(request.conversationId(), email, request.category());

        String reply;
        try {
            reply = chatClient.prompt()
                    .user(category == null ? request.message()
                            : "상담 제품 카테고리: " + category.getLabel() + "\n고객 문의: " + request.message())
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            throw new ChatUnavailableException(exception);
        }

        if (reply == null || reply.isBlank()) {
            throw new ChatUnavailableException(null);
        }

        Long conversationId = conversationService.saveExchange(
                request.conversationId(), email, request.message(), reply, category);
        return new ChatResponse(reply, conversationId);
    }
}
