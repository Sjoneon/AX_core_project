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

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Principal principal) {
        return chatService.chat(request, principal.getName());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> stream(@Valid @RequestBody ChatRequest request, Principal principal) {
        var events = chatService.stream(request, principal.getName());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(events);
    }
}
