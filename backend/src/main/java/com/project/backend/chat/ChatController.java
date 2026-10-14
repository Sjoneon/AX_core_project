package com.project.backend.chat;

import java.security.Principal;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.backend.chat.dto.ChatRequest;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.mcp.agent.AgentChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final AgentChatService agent;
    private final boolean enabled;

    public ChatController(ChatService chatService,AgentChatService agent,@Value("${homecare.agent.enabled:true}") boolean enabled) {
        this.chatService = chatService;
        this.agent=agent;this.enabled=enabled;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Principal principal,
            @RequestHeader("Authorization") String authorization) {
        return enabled?agent.chat(request,principal.getName(),authorization):chatService.chat(request, principal.getName());
    }
}
