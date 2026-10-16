package com.project.backend.conversation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);
    List<ChatMessage> findTop12ByConversationIdOrderByIdDesc(Long conversationId);
    List<ChatMessage> findTop20ByConversationIdOrderByIdDesc(Long conversationId);
    void deleteAllByConversationId(Long conversationId);
    void deleteAllByConversationUserId(Long userId);
}
