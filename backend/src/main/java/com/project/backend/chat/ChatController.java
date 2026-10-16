package com.project.backend.chat;

import java.security.Principal;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.backend.chat.dto.ChatRequest;
import com.project.backend.chat.dto.ChatResponse;
import com.project.backend.mcp.agent.AgentChatService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestHeader;

import reactor.core.publisher.Flux;

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

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> stream(@Valid @RequestBody ChatRequest request, Principal principal) {
        // The frontend retries 404/405 through /api/chat, preserving the agent's tool and safety results.
        if (enabled) return ResponseEntity.notFound().build();
        var events = chatService.stream(request, principal.getName());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(events);
    }
}
