package com.project.backend.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.project.backend.chat.dto.ChatRequest;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.chat.exception.ChatUnavailableException;
import com.project.backend.conversation.ConversationService;

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
        conversationService.checkAccess(request.conversationId(), email);

        String reply;
        try {
            reply = chatClient.prompt()
                    .user(request.message())
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            throw new ChatUnavailableException(exception);
        }

        if (reply == null || reply.isBlank()) {
            throw new ChatUnavailableException(null);
        }

        Long conversationId = conversationService.saveExchange(
                request.conversationId(), email, request.message(), reply);
        return new ChatResponse(reply, conversationId);
    }
}
