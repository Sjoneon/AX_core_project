package com.project.backend.conversation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.project.backend.conversation.dto.ConversationDetail;
import com.project.backend.conversation.dto.ConversationSummary;
import com.project.backend.conversation.dto.MessageResponse;
import com.project.backend.user.AppUserRepository;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AppUserRepository userRepository;

    public ConversationService(ConversationRepository conversationRepository,
            ChatMessageRepository messageRepository, AppUserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(String email) {
        return conversationRepository.findByUserEmailOrderByUpdatedAtDescIdDesc(email).stream()
                .map(ConversationSummary::from).toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetail detail(Long id, String email) {
        Conversation conversation = findOwned(id, email);
        var messages = messageRepository.findByConversationIdOrderByIdAsc(id).stream()
                .map(MessageResponse::from).toList();
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), conversation.getCategory(), messages);
    }

    @Transactional(readOnly = true)
    public ProductCategory resolveCategory(Long id, String email, ProductCategory requestedCategory) {
        if (id != null) {
            ProductCategory savedCategory = findOwned(id, email).getCategory();
            if (requestedCategory != null && requestedCategory != savedCategory) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기존 상담의 카테고리는 변경할 수 없습니다.");
            }
            return savedCategory;
        } else if (!userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요.");
        }
        return requestedCategory;
    }

    // Keep the external AI call outside this transaction; save both messages together.
    @Transactional
    public Long saveExchange(Long id, String email, String message, String reply, ProductCategory category) {
        Conversation conversation;
        if (id == null) {
            var user = userRepository.findByEmail(email).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."));
            conversation = conversationRepository.save(new Conversation(user, message, category));
        } else {
            conversation = findOwned(id, email);
            conversation.touch();
        }
        messageRepository.saveAll(List.of(new ChatMessage(conversation, "user", message),
                new ChatMessage(conversation, "assistant", reply)));
        return conversation.getId();
    }

    private Conversation findOwned(Long id, String email) {
        return conversationRepository.findByIdAndUserEmail(id, email).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."));
    }
}
