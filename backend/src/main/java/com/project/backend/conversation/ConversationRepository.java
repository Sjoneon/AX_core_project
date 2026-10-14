package com.project.backend.conversation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    List<Conversation> findByUserEmailOrderByUpdatedAtDescIdDesc(String email);
    Optional<Conversation> findByIdAndUserEmail(Long id, String email);
    void deleteAllByUserId(Long userId);
    List<Conversation> findByProductIdAndUserEmail(Long productId, String email);
}
