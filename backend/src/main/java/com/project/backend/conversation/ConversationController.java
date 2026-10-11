package com.project.backend.conversation;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.backend.conversation.dto.ConversationDetail;
import com.project.backend.conversation.dto.ConversationSummary;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationSummary> list(Principal principal) {
        return conversationService.list(principal.getName());
    }

    @GetMapping("/{id}")
    public ConversationDetail detail(@PathVariable Long id, Principal principal) {
        return conversationService.detail(id, principal.getName());
    }
}
