package com.project.backend.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.project.backend.chat.dto.ChatRequest;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.chat.exception.ChatUnavailableException;

@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("You are a helpful assistant. Reply in Korean unless the user asks for another language.")
                .build();
    }

    public ChatResponse chat(ChatRequest request) {
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

        return new ChatResponse(reply);
    }
}
